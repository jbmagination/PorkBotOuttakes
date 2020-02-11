/*
 * Adapted from the Wizardry License
 *
 * Copyright (c) 2016-2020 DaPorkchop_
 *
 * Permission is hereby granted to any persons and/or organizations using this software to copy, modify, merge, publish, and distribute it.
 * Said persons and/or organizations are not allowed to use the software or any derivatives of the work for commercial use or any other means to generate income, nor are they allowed to claim this software as their own.
 *
 * The persons and/or organizations are also disallowed from sub-licensing and/or trademarking this software without explicit permission from DaPorkchop_.
 *
 * Any persons and/or organizations using this software must disclose their source code and have it publicly available, include this license, provide sufficient credit to the original authors of the project (IE: DaPorkchop_), as well as provide a link to the original project.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON INFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package net.daporkchop.porkbot.command;

import net.daporkchop.porkbot.util.MessageUtils;
import net.daporkchop.porkbot.util.ObjectDB;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;

import java.io.File;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class CommandRegistry {

    /**
     * A HashMap containing all the commands and their prefix
     */
    public static final HashMap<String, Command> COMMANDS      = new HashMap<>();
    private static final ExecutorService         EXECUTOR      = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    /**
     * Counts all commands run this session
     */
    public static long                           COMMAND_COUNT = 0L;
    /**
     * Counts all commands run
     */
    public static long COMMAND_COUNT_TOTAL;
    private static ObjectDB command_save;

    static {
        command_save = new ObjectDB(new File(System.getProperty("user.dir") + File.separatorChar + "command_info.dat"));
        COMMAND_COUNT_TOTAL = command_save.getLong("totalCommands", 0L);
    }

    /**
     * Registers a command to the command registry.
     *
     * @param cmd
     * @return cmd again lul
     */
    public static final Command registerCommand(Command cmd) {
        cmd.uses = command_save.getInteger(cmd.prefix + "_uses", 0);
        COMMANDS.put(cmd.prefix, cmd);
        return cmd;
    }

    /**
     * Runs a command
     *
     * @param evt
     */
    public static void runCommand(GuildMessageReceivedEvent evt, String rawContent) {
        EXECUTOR.submit(() -> doRunCommand(evt, rawContent));
    }

    private static void doRunCommand(GuildMessageReceivedEvent evt, String rawContent) {
        try {
            try {
                if (evt.getChannel() == null) {
                    return;
                }

                String[] split = rawContent.split(" ");
                Command cmd = COMMANDS.getOrDefault(split[0].substring(2), null);
                if (cmd != null) {
                        evt.getChannel().sendTyping().complete(); //TODO: i don't want to have to wait for this to complete!
                        cmd.execute(evt, split, rawContent);
                        COMMAND_COUNT++;
                        COMMAND_COUNT_TOTAL++;
                        cmd.uses++;
                }
            } catch (Throwable e) {
                e.printStackTrace();
                MessageUtils.sendMessage("Error running command: `" + evt.getMessage().getContentRaw() + "`:\n`" + e.getClass().getCanonicalName() + "`", evt.getChannel());
            }
        } catch (NullPointerException e) {
            evt.getChannel().sendMessage("PorkBot is still starting up! Please wait.").queue();
        }
    }

    public static void save() {
        for (Command cmd : COMMANDS.values()) {
            command_save.setInteger(cmd.prefix + "_uses", cmd.uses);
        }
        command_save.setLong("totalCommands", COMMAND_COUNT_TOTAL);
        command_save.save();
    }
}
