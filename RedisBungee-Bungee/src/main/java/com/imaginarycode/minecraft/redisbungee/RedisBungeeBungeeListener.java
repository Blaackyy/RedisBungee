/*
 * Copyright (c) 2013-present RedisBungee contributors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 *
 *  http://www.eclipse.org/legal/epl-v10.html
 */

package com.imaginarycode.minecraft.redisbungee;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.imaginarycode.minecraft.redisbungee.api.AbstractRedisBungeeListener;
import com.imaginarycode.minecraft.redisbungee.api.config.RedisBungeeConfiguration;
import com.imaginarycode.minecraft.redisbungee.api.util.player.PlayerUtils;
import com.imaginarycode.minecraft.redisbungee.api.RedisBungeePlugin;
import com.imaginarycode.minecraft.redisbungee.api.tasks.RedisTask;
import com.imaginarycode.minecraft.redisbungee.api.util.payload.PayloadUtils;
import com.imaginarycode.minecraft.redisbungee.events.PubSubMessageEvent;
import net.md_5.bungee.api.AbstractReconnectHandler;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import redis.clients.jedis.UnifiedJedis;

import java.net.InetAddress;
import java.util.*;

import static com.imaginarycode.minecraft.redisbungee.api.util.serialize.Serializations.serializeMultimap;
import static com.imaginarycode.minecraft.redisbungee.api.util.serialize.Serializations.serializeMultiset;
import static net.md_5.bungee.event.EventPriority.HIGHEST;

public class RedisBungeeBungeeListener extends AbstractRedisBungeeListener<LoginEvent, PostLoginEvent, PlayerDisconnectEvent, ServerConnectedEvent, ProxyPingEvent, PluginMessageEvent, PubSubMessageEvent> implements Listener {


    public RedisBungeeBungeeListener(RedisBungeePlugin<?> plugin, List<InetAddress> exemptAddresses) {
        super(plugin, exemptAddresses);
    }

    @Override
    @EventHandler(priority = HIGHEST)
    public void onLogin(LoginEvent event) {
        event.registerIntent((Plugin) plugin);
        plugin.executeAsync(new RedisTask<Void>(plugin) {
            @Override
            public Void unifiedJedisTask(UnifiedJedis unifiedJedis) {
                try {
                    if (event.isCancelled()) {
                        return null;
                    }
                    if (plugin.getConfiguration().restoreOldKickBehavior()) {
                        for (String s : plugin.getProxiesIds()) {
                            if (unifiedJedis.sismember("proxy:" + s + ":usersOnline", event.getConnection().getUniqueId().toString())) {
                                event.setCancelled(true);
                                event.setCancelReason(plugin.getConfiguration().getMessages().get(RedisBungeeConfiguration.MessageType.ALREADY_LOGGED_IN));
                                return null;
                            }
                        }
                    } else if (api.isPlayerOnline(event.getConnection().getUniqueId())) {
                        PlayerUtils.setKickedOtherLocation(event.getConnection().getUniqueId().toString(), unifiedJedis);
                        api.kickPlayer(event.getConnection().getUniqueId(), plugin.getConfiguration().getMessages().get(RedisBungeeConfiguration.MessageType.LOGGED_IN_OTHER_LOCATION));
                    }
                    return null;
                } finally {
                    event.completeIntent((Plugin) plugin);
                }
            }
        });
    }

    @Override
    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        plugin.executeAsync(new RedisTask<Void>(plugin) {
            @Override
            public Void unifiedJedisTask(UnifiedJedis unifiedJedis) {
                plugin.getUuidTranslator().persistInfo(event.getPlayer().getName(), event.getPlayer().getUniqueId(), unifiedJedis);
                BungeePlayerUtils.createBungeePlayer(event.getPlayer(), unifiedJedis, true);
                return null;
            }
        });
    }

    @Override
    @EventHandler
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        plugin.executeAsync(new RedisTask<Void>(plugin) {
            @Override
            public Void unifiedJedisTask(UnifiedJedis unifiedJedis) {
                PlayerUtils.cleanUpPlayer(event.getPlayer().getUniqueId().toString(), unifiedJedis, true);
                return null;
            }
        });

    }

    @Override
    @EventHandler
    public void onServerChange(ServerConnectedEvent event) {
        final String currentServer = event.getServer().getInfo().getName();
        final String oldServer = event.getPlayer().getServer() == null ? null : event.getPlayer().getServer().getInfo().getName();
        plugin.executeAsync(new RedisTask<Void>(plugin) {
            @Override
            public Void unifiedJedisTask(UnifiedJedis unifiedJedis) {
                unifiedJedis.hset("player:" + event.getPlayer().getUniqueId().toString(), "server", event.getServer().getInfo().getName());
                PayloadUtils.playerServerChangePayload(event.getPlayer().getUniqueId(), unifiedJedis, currentServer, oldServer);
                return null;
            }
        });
    }

    @Override
    @EventHandler
    public void onPing(ProxyPingEvent event) {
        if (exemptAddresses.contains(event.getConnection().getAddress().getAddress())) {
            return;
        }
        ServerInfo forced = AbstractReconnectHandler.getForcedHost(event.getConnection());

        if (forced != null && event.getConnection().getListener().isPingPassthrough()) {
            return;
        }
        event.getResponse().getPlayers().setOnline(plugin.getCount());
    }

    @Override
    @SuppressWarnings("UnstableApiUsage")
    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if ((event.getTag().equals("legacy:redisbungee") || event.getTag().equals("RedisBungee")) && event.getSender() instanceof Server) {
            final String currentChannel = event.getTag();
            final byte[] data = Arrays.copyOf(event.getData(), event.getData().length);
            plugin.executeAsync(() -> {
                ByteArrayDataInput in = ByteStreams.newDataInput(data);

                String subchannel = in.readUTF();
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                String type;

                switch (subchannel) {
                    case "PlayerList":
                        out.writeUTF("PlayerList");
                        Set<UUID> original = Collections.emptySet();
                        type = in.readUTF();
                        if (type.equals("ALL")) {
                            out.writeUTF("ALL");
                            original = plugin.getPlayers();
                        } else {
                            out.writeUTF(type);
                            try {
                                original = plugin.getAbstractRedisBungeeApi().getPlayersOnServer(type);
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                        Set<String> players = new HashSet<>();
                        for (UUID uuid : original)
                            players.add(plugin.getUuidTranslator().getNameFromUuid(uuid, false));
                        out.writeUTF(Joiner.on(',').join(players));
                        break;
                    case "PlayerCount":
                        out.writeUTF("PlayerCount");
                        type = in.readUTF();
                        if (type.equals("ALL")) {
                            out.writeUTF("ALL");
                            out.writeInt(plugin.getCount());
                        } else {
                            out.writeUTF(type);
                            try {
                                out.writeInt(plugin.getAbstractRedisBungeeApi().getPlayersOnServer(type).size());
                            } catch (IllegalArgumentException e) {
                                out.writeInt(0);
                            }
                        }
                        break;
                    case "LastOnline":
                        String user = in.readUTF();
                        out.writeUTF("LastOnline");
                        out.writeUTF(user);
                        out.writeLong(plugin.getAbstractRedisBungeeApi().getLastOnline(Objects.requireNonNull(plugin.getUuidTranslator().getTranslatedUuid(user, true))));
                        break;
                    case "ServerPlayers":
                        String type1 = in.readUTF();
                        out.writeUTF("ServerPlayers");
                        Multimap<String, UUID> multimap = plugin.getAbstractRedisBungeeApi().getServerToPlayers();

                        boolean includesUsers;

                        switch (type1) {
                            case "COUNT":
                                includesUsers = false;
                                break;
                            case "PLAYERS":
                                includesUsers = true;
                                break;
                            default:
                                // TODO: Should I raise an error?
                                return;
                        }

                        out.writeUTF(type1);

                        if (includesUsers) {
                            Multimap<String, String> human = HashMultimap.create();
                            for (Map.Entry<String, UUID> entry : multimap.entries()) {
                                human.put(entry.getKey(), plugin.getUuidTranslator().getNameFromUuid(entry.getValue(), false));
                            }
                            serializeMultimap(human, true, out);
                        } else {
                            serializeMultiset(multimap.keys(), out);
                        }
                        break;
                    case "Proxy":
                        out.writeUTF("Proxy");
                        out.writeUTF(plugin.getConfiguration().getProxyId());
                        break;
                    case "PlayerProxy":
                        String username = in.readUTF();
                        out.writeUTF("PlayerProxy");
                        out.writeUTF(username);
                        out.writeUTF(plugin.getAbstractRedisBungeeApi().getProxy(Objects.requireNonNull(plugin.getUuidTranslator().getTranslatedUuid(username, true))));
                        break;
                    default:
                        return;
                }

                ((Server) event.getSender()).sendData(currentChannel, out.toByteArray());
            });
        }
    }

    @Override
    @EventHandler
    public void onPubSubMessage(PubSubMessageEvent event) {
        if (event.getChannel().equals("redisbungee-allservers") || event.getChannel().equals("redisbungee-" + plugin.getAbstractRedisBungeeApi().getProxyId())) {
            String message = event.getMessage();
            if (message.startsWith("/"))
                message = message.substring(1);
            plugin.logInfo("Invoking command via PubSub: /" + message);
            ((Plugin) plugin).getProxy().getPluginManager().dispatchCommand(RedisBungeeCommandSender.getSingleton(), message);
        }
    }
}
