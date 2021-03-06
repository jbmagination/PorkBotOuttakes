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

package net.daporkchop.porkbot.command.audio;

import net.daporkchop.lib.common.ref.Ref;
import net.daporkchop.lib.common.ref.ThreadRef;
import net.daporkchop.lib.common.util.PorkUtil;
import net.daporkchop.porkbot.audio.PorkAudio;
import net.daporkchop.porkbot.audio.SearchPlatform;
import net.daporkchop.porkbot.command.Command;
import net.daporkchop.porkbot.util.Constants;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.VoiceChannel;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author DaPorkchop_
 */
public class CommandPlay extends Command {
    private static final Pattern SEARCH_PATTERN = Pattern.compile('^' + Pattern.quote(Constants.COMMAND_PREFIX) + "play (?>(?i)(" + String.join("|", SearchPlatform.getAllPlatformNamesAndAliases()) + ")(?-i) )?(.+)");
    private static final Ref<Matcher> SEARCH_PATTERN_MATCHER_CACHE = ThreadRef.soft(() -> SEARCH_PATTERN.matcher(""));

    public CommandPlay() {
        super("play");
    }

    @Override
    public void execute(GuildMessageReceivedEvent evt, String[] args, String rawContent) {
        Matcher matcher;
        VoiceChannel dstChannel;

        if (args.length < 2 || args[1].isEmpty()) {
            this.sendErrorMessage(evt.getChannel(), "No track URL or search terms given!");
        } else if ((dstChannel = evt.getMember().getVoiceState().getChannel()) == null) {
            evt.getChannel().sendMessage("You must be in a voice channel to play audio!").queue();
        } else if (rawContent.startsWith(Constants.COMMAND_PREFIX + "play http://") || rawContent.startsWith(Constants.COMMAND_PREFIX + "play https://")) {
            String url = rawContent.substring(Constants.COMMAND_PREFIX.length() + "play ".length());
            try {
                url = Constants.escapeUrl(url);
            } catch (Exception e) {
                evt.getChannel().sendMessage("Invalid URL!").queue();
                return;
            }

            PorkAudio.addTrackByURL(evt.getGuild(), evt.getChannel(), evt.getMember(), url, dstChannel);

            if (evt.getGuild().getMember(evt.getJDA().getSelfUser()).hasPermission(evt.getChannel(), Permission.MESSAGE_MANAGE)) {
                evt.getMessage().suppressEmbeds(true).queue();
            }
        } else if ((matcher = SEARCH_PATTERN_MATCHER_CACHE.get()).reset(rawContent).matches()) {
            SearchPlatform platform = SearchPlatform.from(PorkUtil.fallbackIfNull(matcher.group(1), SearchPlatform.YOUTUBE.name()));
            if (platform == null) {
                evt.getChannel().sendMessage("Unknown platform: `" + matcher.group(1) + '`').queue();
            } else {
                PorkAudio.addTrackBySearch(evt.getGuild(), evt.getChannel(), evt.getMember(), platform, matcher.group(2), dstChannel);
            }
        } else {
            throw new IllegalStateException();
        }
    }

    @Override
    public String getUsage() {
        return "..play <url or YouTube search terms>";
    }

    @Override
    public String getUsageExample() {
        return "..play https://cloud.daporkchop.net/random/music/don'tblinkoryou'lldie/don'tblinkoryou'lldie%20-%20Lazy%20Dayze.mp3";
    }
}
