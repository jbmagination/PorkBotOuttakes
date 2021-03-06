/*
 * Adapted from The MIT License (MIT)
 *
 * Copyright (c) 2016-2020 DaPorkchop_
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 *
 * Any persons and/or organizations using this software must include the above copyright notice and this permission notice,
 * provide sufficient credit to the original authors of the project (IE: DaPorkchop_), as well as provide a link to the original project.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package net.daporkchop.porkbot.audio;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageInput;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.DecodedTrackHolder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import net.daporkchop.ldbjni.LevelDB;
import net.daporkchop.lib.common.function.io.IOBiConsumer;
import net.daporkchop.lib.common.function.io.IOConsumer;
import net.daporkchop.lib.common.function.io.IORunnable;
import net.daporkchop.lib.common.misc.file.PFiles;
import net.daporkchop.porkbot.audio.load.FutureSearchLoadResultHandler;
import net.daporkchop.porkbot.audio.load.FutureURLLoadResultHandler;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author DaPorkchop_
 */
@UtilityClass
public class AudioCacheManager {
    private DB TRACK_INFO_DB;

    private final long SEARCH_EXPIRE_TIME = TimeUnit.DAYS.toMillis(14L);

    private final long TRACK_EXPIRE_TIME = TimeUnit.DAYS.toMillis(90L);
    private final LoadingCache<SearchQueryWithPlatform, CompletableFuture<AudioTrack[]>> SEARCH_CACHE = CacheBuilder.newBuilder()
            .expireAfterWrite(5L, TimeUnit.MINUTES)
            .softValues()
            .build(new CacheLoader<SearchQueryWithPlatform, CompletableFuture<AudioTrack[]>>() {
                @Override
                public CompletableFuture<AudioTrack[]> load(SearchQueryWithPlatform query) throws Exception {
                    byte[] encodedKey = query.toString().getBytes(StandardCharsets.UTF_8);
                    byte[] encodedValue = TRACK_INFO_DB.get(encodedKey);

                    AudioTrack[] results;
                    if (encodedValue == null || (results = decodeSearches(encodedValue)) == null) {
                        //value was not found in DB or deserialization failed, execute search
                        CompletableFuture<AudioTrack[]> future = new CompletableFuture<>();
                        PorkAudio.PLAYER_MANAGER.loadItem(query.platform.prefixed(query.query), new FutureSearchLoadResultHandler(future));

                        //save new results
                        future.whenCompleteAsync((IOBiConsumer<AudioTrack[], Throwable>) (result, t) -> {
                            if (t == null && result != null) {
                                TRACK_INFO_DB.put(encodedKey, encodeSearches(result));
                            }
                            System.out.println("Results fetched from service: " + query);
                        });

                        return future;
                    }
                    System.out.println("Results loaded from disk cache: " + query);
                    return CompletableFuture.completedFuture(results);
                }
            });
    private final LoadingCache<String, CompletableFuture<AudioItem>> URL_CACHE = CacheBuilder.newBuilder()
            .expireAfterWrite(5L, TimeUnit.MINUTES)
            .softValues()
            .build(new CacheLoader<String, CompletableFuture<AudioItem>>() {
                @Override
                public CompletableFuture<AudioItem> load(String key) throws Exception {
                    byte[] encodedKey = ("url_" + key).getBytes(StandardCharsets.UTF_8);
                    byte[] encodedValue = TRACK_INFO_DB.get(encodedKey);

                    AudioItem result;
                    if (encodedValue == null || (result = decodeItem(encodedValue)) == null) {
                        //value was not found in DB or deserialization failed, resolve now
                        CompletableFuture<AudioItem> future = new CompletableFuture<>();
                        PorkAudio.PLAYER_MANAGER.loadItem(key, new FutureURLLoadResultHandler(future));

                        //save new info
                        future.whenCompleteAsync((IOBiConsumer<AudioItem, Throwable>) (item, t) -> {
                            if (t == null && item != null) {
                                TRACK_INFO_DB.put(encodedKey, encodeItem(item));
                            }
                            System.out.println("Info fetched from service: " + key);
                        });

                        return future;
                    }
                    System.out.println("Info loaded from disk cache: " + key);
                    return CompletableFuture.completedFuture(result);
                }
            });

    static {
        try {
            TRACK_INFO_DB = LevelDB.PROVIDER.open(PFiles.ensureDirectoryExists(new File("track-info")), new Options()
                    .compressionType(CompressionType.SNAPPY)
                    .verifyChecksums(true)
                    .createIfMissing(true));

            new Thread((IORunnable) () -> {
                System.out.println("Beginning DB compaction...");
                TRACK_INFO_DB.compactRange(null, null);
                System.out.println("Compaction finished.");
            }, "DB compaction thread").start();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    public void shutdown() {
        try {
            TRACK_INFO_DB.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<AudioTrack[]> search(@NonNull SearchQueryWithPlatform query) {
        return SEARCH_CACHE.getUnchecked(query);
    }

    public void search(@NonNull SearchQueryWithPlatform query, @NonNull AudioLoadResultHandler handler) {
        search(query).whenComplete((tracks, t) -> {
            if (t != null)  {
                handler.loadFailed((FriendlyException) t);
            } else if (tracks == null || tracks.length == 0)    {
                handler.noMatches();
            } else if (tracks.length == 1)  {
                handler.trackLoaded(tracks[0]);
            } else {
                handler.playlistLoaded(new BasicAudioPlaylist(null, Arrays.asList(tracks), null, true));
            }
        });
    }

    public CompletableFuture<AudioItem> resolve(@NonNull String url) {
        return URL_CACHE.getUnchecked(url);
    }

    public void resolve(@NonNull String url, @NonNull AudioLoadResultHandler handler) {
        resolve(url).whenComplete((item, t) -> {
            if (t != null) {
                handler.loadFailed((FriendlyException) t);
            } else if (item == null) {
                handler.noMatches();
            } else if (item instanceof AudioTrack) {
                handler.trackLoaded(((AudioTrack) item).makeClone());
            } else if (item instanceof AudioPlaylist) {
                AudioPlaylist plist = (AudioPlaylist) item;
                handler.playlistLoaded(new BasicAudioPlaylist(plist.getName(), plist.getTracks().stream().map(AudioTrack::makeClone).collect(Collectors.toList()), null, false));
            } else {
                throw new IllegalArgumentException(Objects.toString(item));
            }
        });
    }

    private byte[] encodeSearches(AudioTrack[] tracks) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MessageOutput out = new MessageOutput(baos);

        //version and timestamp
        DataOutput dataOutput = out.startMessage();
        dataOutput.writeInt(0);
        dataOutput.writeLong(System.currentTimeMillis());
        out.commitMessage();

        for (AudioTrack track : tracks) {
            PorkAudio.PLAYER_MANAGER.encodeTrack(out, track);
        }
        out.finish();
        return baos.toByteArray();
    }

    private AudioTrack[] decodeSearches(byte[] data) {
        try {
            MessageInput in = new MessageInput(new ByteArrayInputStream(data));
            DataInput dataInput = in.nextMessage();
            if (dataInput.readInt() != 0) { //invalid version
                return null;
            } else if (dataInput.readLong() + SEARCH_EXPIRE_TIME < System.currentTimeMillis()) { //expired
                return null;
            }
            in.skipRemainingBytes();

            List<AudioTrack> tracks = new ArrayList<>();
            for (DecodedTrackHolder holder = PorkAudio.PLAYER_MANAGER.decodeTrack(in); holder != null; holder = PorkAudio.PLAYER_MANAGER.decodeTrack(in)) {
                if (holder.decodedTrack == null) {
                    //invalid version or other deserialization issue
                    return null;
                }
                tracks.add(holder.decodedTrack);
            }
            return tracks.toArray(new AudioTrack[tracks.size()]);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] encodeItem(AudioItem item) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MessageOutput out = new MessageOutput(baos);

        //version and timestamp
        DataOutput dataOutput = out.startMessage();
        dataOutput.writeInt(0);
        dataOutput.writeLong(System.currentTimeMillis());
        dataOutput.writeBoolean(item instanceof AudioPlaylist);
        out.commitMessage();

        if (item instanceof AudioPlaylist) {
            AudioPlaylist plist = (AudioPlaylist) item;
            dataOutput = out.startMessage();
            DataFormatTools.writeNullableText(dataOutput, plist.getName());
            out.commitMessage();

            for (AudioTrack track : plist.getTracks()) {
                PorkAudio.PLAYER_MANAGER.encodeTrack(out, track);
            }
        } else if (item instanceof AudioTrack) {
            PorkAudio.PLAYER_MANAGER.encodeTrack(out, (AudioTrack) item);
        } else {
            throw new IllegalArgumentException(Objects.toString(item));
        }
        out.finish();
        return baos.toByteArray();
    }

    private AudioItem decodeItem(byte[] data) {
        try {
            MessageInput in = new MessageInput(new ByteArrayInputStream(data));
            DataInput dataInput = in.nextMessage();
            if (dataInput.readInt() != 0) { //invalid version
                return null;
            } else if (dataInput.readLong() + SEARCH_EXPIRE_TIME < System.currentTimeMillis()) { //expired
                return null;
            }
            boolean playlist = dataInput.readBoolean();
            in.skipRemainingBytes();

            if (playlist) {
                dataInput = in.nextMessage();
                String name = DataFormatTools.readNullableText(dataInput);
                in.skipRemainingBytes();

                List<AudioTrack> tracks = new ArrayList<>();
                for (DecodedTrackHolder holder = PorkAudio.PLAYER_MANAGER.decodeTrack(in); holder != null; holder = PorkAudio.PLAYER_MANAGER.decodeTrack(in)) {
                    if (holder.decodedTrack == null) {
                        //invalid version or other deserialization issue
                        return null;
                    }
                    tracks.add(holder.decodedTrack);
                }
                return new BasicAudioPlaylist(name, tracks, null, false);
            } else {
                DecodedTrackHolder holder = PorkAudio.PLAYER_MANAGER.decodeTrack(in);
                if (holder == null || holder.decodedTrack == null) {
                    //invalid version or other deserialization issue
                    return null;
                }
                return holder.decodedTrack;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
