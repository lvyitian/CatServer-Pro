package net.minecraft.network;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.io.IOException;
import java.net.*;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.network.NetHandlerHandshakeMemory;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.crash.ICrashReportDetail;
import net.minecraft.network.play.server.SPacketDisconnect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.NetHandlerHandshakeTCP;
import net.minecraft.util.LazyLoadBase;
import net.minecraft.util.ReportedException;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class NetworkSystem
{
    private static final Logger LOGGER = LogManager.getLogger();
    public static final LazyLoadBase<NioEventLoopGroup> SERVER_NIO_EVENTLOOP = new LazyLoadBase<NioEventLoopGroup>()
    {
        protected NioEventLoopGroup load()
        {
            return new NioEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Server IO #%d").setDaemon(true).setThreadFactory(net.minecraftforge.fml.common.thread.SidedThreadGroups.SERVER).build());
        }
    };
    public static final LazyLoadBase<EpollEventLoopGroup> SERVER_EPOLL_EVENTLOOP = new LazyLoadBase<EpollEventLoopGroup>()
    {
        protected EpollEventLoopGroup load()
        {
            return new EpollEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Epoll Server IO #%d").setDaemon(true).setThreadFactory(net.minecraftforge.fml.common.thread.SidedThreadGroups.SERVER).build());
        }
    };
    public static final LazyLoadBase<LocalEventLoopGroup> SERVER_LOCAL_EVENTLOOP = new LazyLoadBase<LocalEventLoopGroup>()
    {
        protected LocalEventLoopGroup load()
        {
            return new LocalEventLoopGroup(0, (new ThreadFactoryBuilder()).setNameFormat("Netty Local Server IO #%d").setDaemon(true).setThreadFactory(net.minecraftforge.fml.common.thread.SidedThreadGroups.CLIENT).build());
        }
    };
    private final MinecraftServer mcServer;
    public volatile boolean isAlive;
    private final List<ChannelFuture> endpoints = Collections.<ChannelFuture>synchronizedList(Lists.newArrayList());
    private final List<NetworkManager> networkManagers = Collections.<NetworkManager>synchronizedList(Lists.newArrayList());
    private boolean field_84041_A;

    public NetworkSystem(MinecraftServer server)
    {
        this.mcServer = server;
        this.isAlive = true;
        ProxySelector defaultSelector = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { defaultSelector.connectFailed(uri, sa, ioe); }
            public ProxySelector getDefaultSelector() { return defaultSelector; }
            public List<Proxy> select(URI uri) {
                if (uri.toString().contains("keepAlive")) field_84041_A = true;
                return defaultSelector.select(uri);
            }
        });
    }

    public void addLanEndpoint(InetAddress address, int port) throws IOException
    {
        if (address instanceof java.net.Inet6Address) System.setProperty("java.net.preferIPv4Stack", "false");
        synchronized (this.endpoints)
        {
            Class <? extends ServerSocketChannel > oclass;
            LazyLoadBase <? extends EventLoopGroup > lazyloadbase;

            if (Epoll.isAvailable() && this.mcServer.shouldUseNativeTransport())
            {
                oclass = EpollServerSocketChannel.class;
                lazyloadbase = SERVER_EPOLL_EVENTLOOP;
                LOGGER.info("Using epoll channel type");
            }
            else
            {
                oclass = NioServerSocketChannel.class;
                lazyloadbase = SERVER_NIO_EVENTLOOP;
                LOGGER.info("Using default channel type");
            }

            this.endpoints.add(((ServerBootstrap)((ServerBootstrap)(new ServerBootstrap()).channel(oclass)).childHandler(new ChannelInitializer<Channel>()
            {
                protected void initChannel(Channel p_initChannel_1_) throws Exception
                {
                    try
                    {
                        p_initChannel_1_.config().setOption(ChannelOption.TCP_NODELAY, Boolean.valueOf(true));
                    }
                    catch (ChannelException var3)
                    {
                        ;
                    }

                    p_initChannel_1_.pipeline().addLast("timeout", new ReadTimeoutHandler(net.minecraftforge.fml.common.network.internal.FMLNetworkHandler.READ_TIMEOUT)).addLast("legacy_query", new LegacyPingHandler(NetworkSystem.this)).addLast("splitter", new NettyVarint21FrameDecoder()).addLast("decoder", new NettyPacketDecoder(EnumPacketDirection.SERVERBOUND)).addLast("prepender", new NettyVarint21FrameEncoder()).addLast("encoder", new NettyPacketEncoder(EnumPacketDirection.CLIENTBOUND));
                    NetworkManager networkmanager = new NetworkManager(EnumPacketDirection.SERVERBOUND);
                    NetworkSystem.this.networkManagers.add(networkmanager);
                    p_initChannel_1_.pipeline().addLast("packet_handler", networkmanager);
                    networkmanager.setNetHandler(new NetHandlerHandshakeTCP(NetworkSystem.this.mcServer, networkmanager));
                }
            }).group(lazyloadbase.getValue()).localAddress(address, port)).bind().syncUninterruptibly());
        }
    }

    @SideOnly(Side.CLIENT)
    public SocketAddress addLocalEndpoint()
    {
        ChannelFuture channelfuture;

        synchronized (this.endpoints)
        {
            channelfuture = ((ServerBootstrap)((ServerBootstrap)(new ServerBootstrap()).channel(LocalServerChannel.class)).childHandler(new ChannelInitializer<Channel>()
            {
                protected void initChannel(Channel p_initChannel_1_) throws Exception
                {
                    NetworkManager networkmanager = new NetworkManager(EnumPacketDirection.SERVERBOUND);
                    networkmanager.setNetHandler(new NetHandlerHandshakeMemory(NetworkSystem.this.mcServer, networkmanager));
                    NetworkSystem.this.networkManagers.add(networkmanager);
                    p_initChannel_1_.pipeline().addLast("packet_handler", networkmanager);
                }
            }).group(SERVER_NIO_EVENTLOOP.getValue()).localAddress(LocalAddress.ANY)).bind().syncUninterruptibly();
            this.endpoints.add(channelfuture);
        }

        return channelfuture.channel().localAddress();
    }

    public void terminateEndpoints()
    {
        this.isAlive = false;

        for (ChannelFuture channelfuture : this.endpoints)
        {
            try
            {
                channelfuture.channel().close().sync();
            }
            catch (InterruptedException var4)
            {
                LOGGER.error("Interrupted whilst closing channel");
            }
        }
    }

    public void networkTick()
    {
        synchronized (this.networkManagers)
        {
            // Spigot Start
            // This prevents players from 'gaming' the server, and strategically relogging to increase their position in the tick order
            if (org.spigotmc.SpigotConfig.playerShuffle > 0 && MinecraftServer.currentTick % org.spigotmc.SpigotConfig.playerShuffle == 0)
            {
                Collections.shuffle(this.networkManagers);
            }
            // Spigot End
            Iterator<NetworkManager> iterator = this.networkManagers.iterator();

            while (iterator.hasNext())
            {
                final NetworkManager networkmanager = iterator.next();

                if (!networkmanager.hasNoChannel())
                {
                    if (networkmanager.isChannelOpen())
                    {
                        try
                        {
                            //if (this.field_84041_A || mcServer.getTickCounter() < 144000) // 暗桩 144000tick = 两小时 删除
                            networkmanager.processReceivedPackets();
                        }
                        catch (Exception exception)
                        {
                            if (networkmanager.isLocalChannel())
                            {
                                CrashReport crashreport = CrashReport.makeCrashReport(exception, "Ticking memory connection");
                                CrashReportCategory crashreportcategory = crashreport.makeCategory("Ticking connection");
                                crashreportcategory.addDetail("Connection", new ICrashReportDetail<String>()
                                {
                                    public String call() throws Exception
                                    {
                                        return networkmanager.toString();
                                    }
                                });
                                throw new ReportedException(crashreport);
                            }

                            LOGGER.warn("Failed to handle packet for {}", networkmanager.getRemoteAddress(), exception);
                            final TextComponentString textcomponentstring = new TextComponentString("Internal server error");
                            networkmanager.sendPacket(new SPacketDisconnect(textcomponentstring), new GenericFutureListener < Future <? super Void >> ()
                            {
                                public void operationComplete(Future <? super Void > p_operationComplete_1_) throws Exception
                                {
                                    networkmanager.closeChannel(textcomponentstring);
                                }
                            });
                            networkmanager.disableAutoRead();
                        }
                    }
                    else
                    {
                        // Spigot Start - Fix a race condition where a NetworkManager could be unregistered just before connection.
                        if (networkmanager.preparing) continue;
                        // Spigot End
                        iterator.remove();
                        networkmanager.checkDisconnected();
                    }
                }
            }
        }
    }

    public MinecraftServer getServer()
    {
        return this.mcServer;
    }
}