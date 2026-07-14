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
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        ViewHolder holder;
        if(convertView == null) {
            convertView = mInflater.inflate(R.layout.item_wallpaper_select, parent, false);
            holder = new ViewHolder();
            holder.check = convertView.findViewById(R.id.checkBoxWallpaperVisible);
            holder.thumb = convertView.findViewById(R.id.imageViewWallpaperThumb);
            holder.title = convertView.findViewById(R.id.textViewWallpaperName);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        WallpaperItem item = getItem(position);
        holder.check.setChecked(item.url != null && mVisibleUrls.contains(item.url));
        holder.title.setText(describe(item));
        loadThumb(holder.thumb, item);
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

    private static boolean isRemote(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
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
        Glide.with(mContext.getApplicationContext())
                .load(model)
                .centerCrop()
                .into(view);
    }

    private static class ViewHolder {
        CheckBox check;
        ImageView thumb;
        TextView title;
    }
}
