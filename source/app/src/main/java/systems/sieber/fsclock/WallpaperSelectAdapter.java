package systems.sieber.fsclock;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
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
    private final List<WallpaperItem> mItems;
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
        mItems = items;
        for(WallpaperItem it : items) {
            if(it.url != null && !hiddenUrls.contains(it.url)) mVisibleUrls.add(it.url);
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

    /** Flip one row between shown and hidden. */
    void toggle(int position) {
        WallpaperItem item = getItem(position);
        if(item.url == null) return;
        if(!mVisibleUrls.remove(item.url)) mVisibleUrls.add(item.url);
        notifyDataSetChanged();
    }

    /** Check or uncheck every row at once. */
    void setAllVisible(boolean visible) {
        mVisibleUrls.clear();
        if(visible) {
            for(WallpaperItem it : mItems) if(it.url != null) mVisibleUrls.add(it.url);
        }
        notifyDataSetChanged();
    }

    boolean areAllVisible() {
        return mVisibleUrls.size() >= mItems.size();
    }

    int getVisibleCount() {
        return mVisibleUrls.size();
    }

    /** The urls to persist as hidden: everything currently listed that is not checked. */
    List<String> getHiddenUrls() {
        List<String> hidden = new ArrayList<>();
        for(WallpaperItem it : mItems) {
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
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final WallpaperItem item = getItem(position);
        boolean visible = item.url != null && mVisibleUrls.contains(item.url);
        holder.check.setChecked(visible);
        holder.title.setText(describe(item));
        loadThumb(holder.thumb, item);
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
        convertView.setOnClickListener(v -> toggle(position));
        holder.edit.setOnClickListener(v -> {
            if(mEditListener != null) mEditListener.onEdit(item);
        });

        // The bin only exists for a file this device owns. A cloud wallpaper (public or
        // assigned to this car) belongs to the shop's library — the car can hide it or edit a
        // local copy of it, but it cannot delete somebody else's picture, and pretending
        // otherwise would produce a "deleted" image that the next sync puts straight back.
        boolean deletable = mDeleteListener != null && !isRemote(item.url);
        holder.delete.setVisibility(deletable ? View.VISIBLE : View.GONE);
        holder.delete.setOnClickListener(deletable ? v -> mDeleteListener.onDelete(item) : null);
        return convertView;
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
     * Videos have no thumbnail unless the file is already on disk (Glide decodes a frame
     * from a local file, not from a remote stream), so a not-yet-cached remote video falls
     * back to a plain icon rather than an endless spinner.
     */
    private void loadThumb(ImageView view, WallpaperItem item) {
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
            return;
        }
        // Show it the way the car will: straighten first, then crop to the cell. Rotating after
        // the crop would turn the cell's own framing, not the picture inside it.
        int rot = (mRepo != null && item.url != null)
                ? FitSettings.clampRotation(mRepo.getFit(item.url).rotation) : 0;
        com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> req =
                Glide.with(mContext.getApplicationContext()).load(model);
        req = rot == 0
                ? req.centerCrop()
                : req.transform(new RotateTransformation(rot),
                                new com.bumptech.glide.load.resource.bitmap.CenterCrop());
        req.into(view);
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
