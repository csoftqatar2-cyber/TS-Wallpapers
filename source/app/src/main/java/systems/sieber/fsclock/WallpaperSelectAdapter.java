package systems.sieber.fsclock;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lists every wallpaper available to this device with a checkbox, so the shop owner can
 * hide individual ones on this car only (see {@link WallpaperRepo#PREF_HIDDEN}).
 * Checked = shown in the slideshow.
 */
public class WallpaperSelectAdapter extends BaseAdapter {

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final WallpaperRepo mRepo;
    /** Everything the dialog was opened with; the hide state is always keyed on this list. */
    private final List<WallpaperItem> mAll;
    /** What the grid shows right now: mAll, or only videos / only images (see setTypeFilter). */
    private List<WallpaperItem> mItems;
    private final Set<String> mVisibleUrls = new HashSet<>();

    /** Told which row's edit button was pressed. */
    interface OnEditListener {
        void onEdit(WallpaperItem item);
    }

    /** Told which row's bin was pressed. Only ever fires for images stored on this device. */
    interface OnDeleteListener {
        void onDelete(WallpaperItem item);
    }

    private OnEditListener mEditListener;
    private OnDeleteListener mDeleteListener;

    void setOnEditListener(OnEditListener l) { mEditListener = l; }

    void setOnDeleteListener(OnDeleteListener l) { mDeleteListener = l; }

    /** Drop one row after its file was deleted, without rebuilding the whole dialog. */
    void removeItem(WallpaperItem item) {
        if(item == null) return;
        mItems.remove(item);
        if(item.url != null) mVisibleUrls.remove(item.url);
        notifyDataSetChanged();
    }

    WallpaperSelectAdapter(Context context, WallpaperRepo repo, List<WallpaperItem> items, Set<String> hiddenUrls) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
        mRepo = repo;
        mAll = items;
        mItems = items;
        // The stored hide list may have been written against the host the library used to be
        // served from, so match on the wallpaper's identity rather than on the raw url —
        // otherwise every tick the shop set would silently clear the day we move hosts.
        Set<String> hiddenKeys = new HashSet<>();
        for(String h : hiddenUrls) {
            String k = WallpaperRepo.wallpaperKey(h);
            if(k != null) hiddenKeys.add(k);
        }
        for(WallpaperItem it : items) {
            if(it.url != null && !hiddenKeys.contains(WallpaperRepo.wallpaperKey(it.url))) {
                mVisibleUrls.add(it.url);
            }
        }
    }

    @Override
    public int getCount() {
        return mItems.size();
    }

    @Override
    public WallpaperItem getItem(int position) {
        return mItems.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    /**
     * Flip one cell between shown and hidden, repainting only that cell.
     *
     * Deliberately not notifyDataSetChanged(). The grid is built once from getView() and a
     * broadcast change rebuilds every cell, which means re-requesting every thumbnail — on a
     * head unit, a visible stutter and a flash of empty frames every time somebody ticks a box.
     * Nothing about the other cells has changed, so nothing else is touched.
     */
    void toggle(int position, View cell, CheckBox check) {
        WallpaperItem item = getItem(position);
        if(item.url == null) return;
        if(!mVisibleUrls.remove(item.url)) mVisibleUrls.add(item.url);
        boolean visible = mVisibleUrls.contains(item.url);
        if(check != null) check.setChecked(visible);
        if(cell != null) cell.setActivated(visible);
    }

    /**
     * Owner's filter semantics (2026-09-05): nothing lit or both lit = every wallpaper; exactly one
     * lit = that type only. Purely a view over mAll — ticks are kept for the cells that are hidden
     * by the filter, so saving never un-hides or hides anything the person did not touch.
     */
    void setTypeFilter(boolean videos, boolean images) {
        if(videos == images) { mItems = mAll; notifyDataSetChanged(); return; }
        List<WallpaperItem> shown = new ArrayList<>();
        for(WallpaperItem it : mAll) if(it.isVideo() == videos) shown.add(it);
        mItems = shown;
        notifyDataSetChanged();
    }

    /** Check or uncheck every row at once. */
    void setAllVisible(boolean visible) {
        mVisibleUrls.clear();
        if(visible) {
            for(WallpaperItem it : mAll) if(it.url != null) mVisibleUrls.add(it.url);
        }
        notifyDataSetChanged();
    }

    boolean areAllVisible() {
        return mVisibleUrls.size() >= mAll.size();
    }

    int getVisibleCount() {
        return mVisibleUrls.size();
    }

    /** The urls to persist as hidden: everything currently listed that is not checked. */
    List<String> getHiddenUrls() {
        List<String> hidden = new ArrayList<>();
        for(WallpaperItem it : mAll) {
            if(it.url != null && !mVisibleUrls.contains(it.url)) hidden.add(it.url);
        }
        return hidden;
    }

    @Override
    public View getView(final int position, View convertView, @NonNull ViewGroup parent) {
        ViewHolder holder;
        if(convertView == null) {
            convertView = mInflater.inflate(R.layout.item_wallpaper_select, parent, false);
            holder = new ViewHolder();
            holder.check = convertView.findViewById(R.id.checkBoxWallpaperVisible);
            holder.thumb = convertView.findViewById(R.id.imageViewWallpaperThumb);
            holder.title = convertView.findViewById(R.id.textViewWallpaperName);
            holder.edit = convertView.findViewById(R.id.buttonWallpaperEdit);
            holder.delete = convertView.findViewById(R.id.buttonWallpaperDelete);
            clipToCellShape(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        // Size the cell BEFORE loading anything into it. Glide answers a memory-cache hit
        // synchronously, from inside loadThumb below — so if the host set the LayoutParams
        // afterwards, sizeCellToImage would run against a view that had none yet, find nothing to
        // resize, and then be overwritten by the 16:9 placeholder. Every picture that was not 16:9
        // then sat letterboxed inside a border that was wider than it was. It looked right the
        // first time the dialog opened (nothing cached, so the callbacks were late and landed on a
        // sized cell) and wrong on every open after that, which is what made it look like a
        // scrolling bug.
        applyCellSize(convertView);

        final WallpaperItem item = getItem(position);
        final View cell = convertView;
        boolean visible = item.url != null && mVisibleUrls.contains(item.url);
        holder.check.setChecked(visible);
        holder.title.setText(describe(item));
        loadThumb(holder.thumb, item, cell);
        // Drives the cell outline through the state list, so a ticked cell is readable from
        // across the cabin and not just by a 20px box in its corner.
        convertView.setActivated(visible);

        // Both wired here rather than through the GridView's own item click.
        //
        // An AdapterView only delivers a row click when NOTHING in the row is focusable, so the
        // moment this cell grew an edit button the tick stopped working entirely. Per-cell
        // listeners have no such rule, and they also let the two targets differ: the cell
        // toggles, the pencil edits.
        //
        // The listener goes on the cell, not on the thumbnail: the scrim and the name sit on top
        // of the image, so a tap near the top or bottom edge would miss a thumbnail listener.
        //
        // Rebound every getView, because cells are recycled — a listener that captured the old
        // item would act on whatever used to be in this cell.
        final ViewHolder bound = holder;
        convertView.setOnClickListener(v -> toggle(position, v, bound.check));

        // The pencil is for stills only. Every control on the fit editor — crop, zoom, rotation,
        // the blurred or coloured bars — describes how ONE frame is laid onto the screen, and a
        // clip has no single frame to lay out; its framing is decided by the player while it
        // runs. So on a video the button opened a screen that could not change anything about it.
        // Hidden rather than disabled: a greyed pencil still says "there is an edit here".
        final boolean editable = !item.isVideo();
        holder.edit.setVisibility(editable ? View.VISIBLE : View.GONE);
        holder.edit.setOnClickListener(editable ? v -> {
            if(mEditListener != null) mEditListener.onEdit(item);
        } : null);

        // The bin only exists for a file this device owns. A cloud wallpaper (public or
        // assigned to this car) belongs to the shop's library — the car can hide it or edit a
        // local copy of it, but it cannot delete somebody else's picture, and pretending
        // otherwise would produce a "deleted" image that the next sync puts straight back.
        boolean deletable = mDeleteListener != null && !isRemote(item.url);
        holder.delete.setVisibility(deletable ? View.VISIBLE : View.GONE);
        holder.delete.setOnClickListener(deletable ? v -> mDeleteListener.onDelete(item) : null);
        return convertView;
    }

    /**
     * Reshape a cell so its frame ends exactly where the picture does.
     *
     * The column decides the width, so the height is the free dimension: width x the picture's own
     * aspect ratio. This is the whole answer to "the border is wider than the wallpaper inside
     * it" — the outline is drawn around the cell, so the only way to make it trace the photo is
     * to make the cell the photo's shape.
     *
     * The clamps catch the extremes only. Left unbounded, a portrait phone snap would make a cell
     * several times the height of its neighbours, and in a two-column grid that is a hole in the
     * layout rather than a big picture.
     */
    private static void sizeCellToImage(View cell, int imgW, int imgH) {
        if(cell == null || imgW <= 0 || imgH <= 0) return;
        ViewGroup.LayoutParams lp = cell.getLayoutParams();
        if(lp == null || lp.width <= 0) return;
        int h = Math.round(lp.width * (imgH / (float) imgW));
        h = Math.max(Math.round(lp.width * 0.30f), Math.min(Math.round(lp.width * 1.20f), h));
        if(h == lp.height) return;
        lp.height = h;
        cell.setLayoutParams(lp);
    }

    /**
     * The column width the host grid wants, and the gap it wants between cells.
     *
     * The adapter owns the cell's LayoutParams rather than the caller, purely so they can be in
     * place before the thumbnail request starts. See the note in getView.
     */
    private int mCellWidth, mCellGap;

    void setCellSize(int width, int gap) {
        mCellWidth = width;
        mCellGap = gap;
    }

    /** The starting shape of a cell: one column wide, 16:9, because most wallpapers are. */
    private void applyCellSize(View cell) {
        if(mCellWidth <= 0) return;
        ViewGroup.LayoutParams existing = cell.getLayoutParams();
        GridLayout.LayoutParams lp = existing instanceof GridLayout.LayoutParams
                ? (GridLayout.LayoutParams) existing : new GridLayout.LayoutParams();
        lp.width = mCellWidth;
        lp.height = Math.round(mCellWidth / 1.78f);
        lp.setMargins(0, 0, mCellGap, mCellGap);
        cell.setLayoutParams(lp);
    }

    /**
     * Clip a cell to the same rounded rectangle its ring draws.
     *
     * The ring is a 16dp-radius stroke drawn over the top of the picture; the picture itself is a
     * plain rectangle. Without this its square corners are drawn outside the curve — four small
     * nubs of image poking out of a rounded frame. Set once per inflated cell, not per bind: the
     * shape does not depend on which wallpaper is currently in it.
     *
     * Must stay in step with the corner radius in wp_grid_cell_ring.xml.
     */
    private static void clipToCellShape(View cell) {
        final float radius = 16 * cell.getResources().getDisplayMetrics().density;
        cell.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        cell.setClipToOutline(true);
    }

    /** "name.jpg — فيديو (من السيرفر)" */
    private String describe(WallpaperItem item) {
        return fileName(item.url) + " — " + typeLabel(item) + " (" + sourceLabel(item) + ")";
    }

    private String typeLabel(WallpaperItem item) {
        if(item.isVideo()) return mContext.getString(R.string.wallpaper_type_video);
        if(WallpaperItem.TYPE_GIF.equals(item.type)) return mContext.getString(R.string.wallpaper_type_gif);
        return mContext.getString(R.string.wallpaper_type_image);
    }

    private String sourceLabel(WallpaperItem item) {
        return isRemote(item.url)
                ? mContext.getString(R.string.wallpaper_source_remote)
                : mContext.getString(R.string.wallpaper_source_local);
    }

    /** One definition of "remote", shared with the repo — the bin's visibility depends on it. */
    private static boolean isRemote(String url) {
        return WallpaperRepo.isRemoteUrl(url);
    }

    private static String fileName(String url) {
        if(url == null || url.trim().isEmpty()) return "?";
        String u = url;
        int q = u.indexOf('?');
        if(q >= 0) u = u.substring(0, q);
        int slash = Math.max(u.lastIndexOf('/'), u.lastIndexOf('\\'));
        if(slash >= 0 && slash < u.length() - 1) u = u.substring(slash + 1);
        return Uri.decode(u);
    }

    /**
     * How large a thumbnail is decoded, in pixels.
     *
     * An explicit box rather than "size it to the view". Sizing to the view is circular here: the
     * cell's height is what we are trying to derive FROM the picture, so asking Glide to fit the
     * picture to the cell first would hand back a bitmap in whatever shape the cell happened to
     * start at. A fixed box breaks the loop, and because it is a box (not a rectangle) the bitmap
     * that comes back keeps the picture's OWN ratio, which is exactly what the cell needs.
     */
    private static final int THUMB_PX = 480;

    /**
     * Videos have no thumbnail unless the file is already on disk (Glide decodes a frame
     * from a local file, not from a remote stream), so a not-yet-cached remote video falls
     * back to a plain icon rather than an endless spinner.
     */
    private void loadThumb(ImageView view, WallpaperItem item, final View cell) {
        Object model = null;
        if(item.isVideo()) {
            String local = (mRepo != null) ? mRepo.localVideoPath(item) : null;
            if(local != null) model = new File(local);
            else if(!isRemote(item.url) && item.url != null) model = new File(item.url);
        } else if(item.url != null) {
            model = isRemote(item.url) || item.url.startsWith("content://") || item.url.startsWith("file://")
                    ? (Object) item.url
                    : new File(item.url);
        }

        if(model == null) {
            Glide.with(mContext.getApplicationContext()).clear(view);
            view.setImageResource(R.drawable.ic_play_pause_white);
            // Nothing to take a shape from, so the cell keeps whatever the grid gave it and the
            // icon is centred in it rather than stretched across it.
            view.setScaleType(ImageView.ScaleType.CENTER);
            return;
        }
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // The WHOLE wallpaper, never a crop — and the cell reshaped to match it, which is what
        // makes the outline land on the photo instead of around a box the photo sits inside.
        //
        // Rotation goes first: turning the picture after it was framed would rotate the framing,
        // not the contents. It also changes the ratio, which is why the cell is sized from the
        // delivered drawable rather than from the file's own dimensions.
        int rot = (mRepo != null && item.url != null)
                ? FitSettings.clampRotation(mRepo.getFit(item.url).rotation) : 0;
        com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> req =
                Glide.with(mContext.getApplicationContext()).load(model).override(THUMB_PX, THUMB_PX);
        req = rot == 0
                ? req.fitCenter()
                : req.transform(new RotateTransformation(rot),
                                new com.bumptech.glide.load.resource.bitmap.FitCenter());
        req.listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
            @Override
            public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object m,
                                        com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> t,
                                        boolean first) {
                return false;   // the cell keeps the provisional shape the grid gave it
            }
            @Override
            public boolean onResourceReady(android.graphics.drawable.Drawable res, Object m,
                                           com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> t,
                                           com.bumptech.glide.load.DataSource src, boolean first) {
                sizeCellToImage(cell, res.getIntrinsicWidth(), res.getIntrinsicHeight());
                return false;
            }
        }).into(view);
    }

    /**
     * Types here must match item_wallpaper_select.xml exactly. findViewById infers its return
     * type from the field, so a mismatch compiles cleanly and throws ClassCastException at the
     * first inflate — which is to say, on the device, in front of the customer. `edit` was left
     * as a TextView when the button became a pencil ImageView, and that is precisely what
     * happened.
     */
    private static class ViewHolder {
        CheckBox check;
        ImageView thumb;
        TextView title;
        ImageView edit;
        ImageView delete;
    }
}
