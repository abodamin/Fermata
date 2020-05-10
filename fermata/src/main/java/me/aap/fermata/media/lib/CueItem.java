package me.aap.fermata.media.lib;

import android.content.Context;
import android.support.v4.media.MediaDescriptionCompat;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.R;
import me.aap.fermata.media.engine.MetadataBuilder;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.util.Utils;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.log.Log;
import me.aap.utils.text.SharedTextBuilder;
import me.aap.utils.vfs.VirtualFile;
import me.aap.utils.vfs.VirtualFolder;
import me.aap.utils.vfs.VirtualResource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static me.aap.fermata.BuildConfig.DEBUG;
import static me.aap.utils.async.Completed.completed;

/**
 * @author Andrey Pavlenko
 */
class CueItem extends BrowsableItemBase {
	public static final String SCHEME = "cue";
	private final List<CueTrackItem> tracks;

	private CueItem(String id, BrowsableItem parent, VirtualFolder dir, VirtualFile cueFile) {
		super(id, parent, cueFile);

		Context ctx = parent.getLib().getContext();
		List<CueTrackItem> tracks = new ArrayList<>();
		VirtualResource file = null;
		String fileName = null;
		String track = null;
		String title = null;
		String performer = null;
		String writer = null;
		String index = null;
		String albumTitle = null;
		String albumPerformer = null;
		String albumWriter = null;
		boolean isVideo = false;
		boolean wasTrack = false;

		try (BufferedReader r = new BufferedReader(new InputStreamReader(
				cueFile.getInputStream().asInputStream(), UTF_8));
				 SharedTextBuilder tb = SharedTextBuilder.get()) {
			for (String l = r.readLine(); l != null; l = r.readLine()) {
				l = l.trim();

				if (l.startsWith("REM ")) {
					//noinspection UnnecessaryContinue
					continue;
				} else if (l.startsWith("TRACK ")) {
					if (wasTrack) {
						addTrack(id, tracks, file, track, title, performer, writer, index, albumTitle,
								albumPerformer, albumWriter, isVideo, tb);
						title = performer = writer = albumTitle = albumPerformer = albumWriter = null;
					} else {
						wasTrack = true;
					}

					track = l;
				} else if (l.startsWith("TITLE ")) {
					if (wasTrack) {
						title = rmQuotes(l.substring(6));
					} else {
						albumTitle = rmQuotes(l.substring(6));
					}
				} else if (l.startsWith("INDEX 01 ")) {
					index = rmQuotes(l.substring(9));
				} else if (l.startsWith("PERFORMER ")) {
					if (wasTrack) {
						performer = rmQuotes(l.substring(10));
					} else {
						albumPerformer = rmQuotes(l.substring(10));
					}
				} else if (l.startsWith("SONGWRITER ")) {
					if (wasTrack) {
						writer = rmQuotes(l.substring(11));
					} else {
						albumWriter = rmQuotes(l.substring(11));
					}
				} else if (l.startsWith("FILE ")) {
					if (wasTrack) {
						addTrack(id, tracks, file, track, title, performer, writer, index, albumTitle,
								albumPerformer, albumWriter, isVideo, tb);
						track = title = performer = writer = index = albumTitle = albumPerformer = albumWriter = null;
					}

					String name = rmQuotes(l.substring(5));

					if (!name.equals(fileName)) {
						fileName = name;
						file = dir.getChild(name).get(null);
						isVideo = (file != null) && Utils.isVideoFile(file.getName());
					}
				}
			}

			addTrack(id, tracks, file, track, title, performer, writer, index, albumTitle,
					albumPerformer, albumWriter, isVideo, tb);

			int size = tracks.size();

			if (size > 0) {
				CueTrackItem last = tracks.get(size - 1);
				MetadataBuilder md = getLib().getMetadataRetriever().getMediaMetadata(last).getOrThrow();
				long dur = md.getDuration();
				if (dur > 0) last.duration(dur - last.getOffset());
			}
		} catch (Exception ex) {
			Log.e(ex, "Failed to parse cue file: ", getFile());
		}

		MediaDescriptionCompat.Builder dsc = new MediaDescriptionCompat.Builder();
		dsc.setTitle((albumTitle != null) ? albumTitle : cueFile.getName());
		dsc.setSubtitle(ctx.getResources().getString(R.string.browsable_subtitle, tracks.size()));
		setMediaDescription(dsc.build());
		this.tracks = tracks;
	}

	static CueItem create(String id, BrowsableItem parent, VirtualFolder dir, VirtualFile cueFile,
												DefaultMediaLib lib) {
		synchronized (lib.cacheLock()) {
			Item i = lib.getFromCache(id);

			if (i != null) {
				CueItem c = (CueItem) i;
				if (DEBUG && !parent.equals(c.getParent())) throw new AssertionError();
				if (DEBUG && !cueFile.equals(c.getFile())) throw new AssertionError();
				return c;
			} else {
				return new CueItem(id, parent, dir, cueFile);
			}
		}
	}

	@NonNull
	static FutureSupplier<Item> create(DefaultMediaLib lib, String id) {
		assert id.startsWith(SCHEME);
		SharedTextBuilder tb = SharedTextBuilder.get();
		tb.append(FileItem.SCHEME).append(id, SCHEME.length(), id.length());

		return lib.getItem(tb.releaseString()).map(i -> {
			FileItem file = (FileItem) i;
			if (file == null) return null;

			FolderItem parent = (FolderItem) file.getParent();
			return create(id, parent, parent.getFile(), (VirtualFile) file.getFile(), lib);
		});
	}

	static boolean isCueFile(String name) {
		return name.endsWith(".cue");
	}

	private static String rmQuotes(String s) {
		int first = s.indexOf("\"");
		int last = s.lastIndexOf("\"");
		return (first < last) ? s.substring(first + 1, last) : s;
	}

	private void addTrack(String id, List<CueTrackItem> tracks, VirtualResource file, String track, String title,
												String performer, String writer, String index, String albumTitle,
												String albumPerformer, String albumWriter, boolean isVideo,
												SharedTextBuilder tb) {
		if ((file == null) || (track == null) || (index == null)) return;

		String[] i = index.split(":");
		if (i.length != 3) return;

		long m = Long.parseLong(i[0]);
		int s = Integer.parseInt(i[1]);
		int f = Integer.parseInt(i[2]);
		long offset = m * 60000 + s * 1000 + (long) (((double) f / 74) * 1000);

		if (title == null) {
			title = (albumTitle != null) ? albumTitle : file.getName();
		}

		if ((performer == null) && (albumPerformer != null)) {
			performer = albumPerformer;
		}

		if ((writer == null) && (albumWriter != null)) {
			writer = albumWriter;
		}

		int trackNum = tracks.size() + 1;
		tb.setLength(0);
		tb.append(CueTrackItem.SCHEME).append(':').append(trackNum)
				.append(id, SCHEME.length(), id.length());
		CueTrackItem t = new CueTrackItem(tb.toString(), this, trackNum, file, title,
				performer, writer, albumTitle, offset, isVideo);
		tracks.add(t);

		if (trackNum > 1) {
			CueTrackItem prev = tracks.get(trackNum - 2);
			prev.duration(offset - prev.getOffset());
		}
	}

	public CueTrackItem getTrack(int id) {
		if (id > tracks.size()) return null;

		CueTrackItem t = tracks.get(id - 1);
		if (t.getTrackNumber() == id) return t;

		for (CueTrackItem c : tracks) {
			if (c.getTrackNumber() == id) return c;
		}

		return null;
	}

	@Override
	public int getIcon() {
		return R.drawable.cue;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Override
	public FutureSupplier<List<Item>> listChildren() {
		return completed((List) tracks);
	}
}
