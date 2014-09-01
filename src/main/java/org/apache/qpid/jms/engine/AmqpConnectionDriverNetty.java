/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.jms.engine;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.qpid.jms.engine.temp.AbstractEventHandler;
import org.apache.qpid.jms.engine.temp.EventHandler;
import org.apache.qpid.jms.engine.temp.Events;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.engine.Collector;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.Event;
import org.apache.qpid.proton.engine.Sasl;
import org.apache.qpid.proton.engine.Transport;

public class AmqpConnectionDriverNetty
{
    private static Logger LOGGER = Logger.getLogger(AmqpConnectionDriverNetty.class.getName());
    private static Logger LOGGER_BYTES = Logger.getLogger(AmqpConnectionDriverNetty.class.getName() + ".BYTES");

    private Bootstrap _bootstrap;
    private NettyHandler _nettyHandler;

    public AmqpConnectionDriverNetty()
    {
    }

    public void registerConnection(AmqpConnection amqpConnection)
    {
        //TODO: make config options configurable
        int connectTimeoutMillis = 30000;
        boolean autoRead = false;
        boolean tcpNoDelay = true;
        boolean tcpKeepAlive = true;

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.channel(NioSocketChannel.class);
        EventLoopGroup group = new NioEventLoopGroup();
        bootstrap.group(group);

        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis);
        bootstrap.option(ChannelOption.AUTO_READ, autoRead);
        bootstrap.option(ChannelOption.TCP_NODELAY, tcpNoDelay);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, tcpKeepAlive);

        bootstrap.option(ChannelOption.ALLOCATOR, new UnpooledByteBufAllocator(false));

        _nettyHandler = new NettyHandler(amqpConnection);
        bootstrap.handler(new ChannelInitializer<SocketChannel>()
        {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();
                p.addLast(_nettyHandler);
            }
        });

        _bootstrap = bootstrap;

        String remoteHost = amqpConnection.getRemoteHost();
        int port = amqpConnection.getPort();

        ChannelFuture future = _bootstrap.connect(remoteHost, port);
        future.awaitUninterruptibly();

        if (future.isSuccess())
        {
            //TODO connected, do anything extra required (e.g wait for successful SSL handshake).
        }
        else
        {
            Throwable t = future.cause();

            throw new RuntimeException("Failed to connect", t);
        }
    }

    private class NettyHandler extends ChannelInboundHandlerAdapter
    {
        private boolean dispatching = false;
        private Transport _transport;
        private Collector _collector;
        private List<EventHandler> handlers = new CopyOnWriteArrayList<EventHandler>();
        private AmqpConnection _amqpConnection;

        private NettyHandler(AmqpConnection amqpConnection)
        {
            _amqpConnection = amqpConnection;
            addEventHandler(_amqpConnection.getEventHandler());
            addEventHandler(new NettyWriter());
        }

        private class NettyWriter extends AbstractEventHandler
        {
            @Override
            public void onTransport(Transport transport)
            {
                ChannelHandlerContext ctx = (ChannelHandlerContext) transport.getContext();
                write(ctx);
                scheduleReadIfCapacity(transport, ctx);
            }

/*          This was disabled after adding the 'write after read' workaround for SASL negotiation in the channelRead(..) handler.
            If that is removed e.g after making the SASL layer event-friendly, this will become an issue again if the client isn't smarter.

            @Override
            public void onFlow(Link link)
            {
                //TODO: delete, somehow.
                //This is only here because the client currently sends its messages without checking for credit,
                //which can result in the flow arriving after the send attempt, which results in no transport event
                //being emitted to signal the message can now be written, and so it may never get sent.

                logMessage("Forcing transport write attempt after flow");
                ChannelHandlerContext ctx = (ChannelHandlerContext) _transport.getContext();
                write(ctx);
                scheduleReadIfCapacity(_transport, ctx);
            }*/
        }

        public void addEventHandler(EventHandler handler)
        {
            handlers.add(handler);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx)
        {
            synchronized (_amqpConnection)
            {
                logMessage("ACTIVE");
                _transport = Transport.Factory.create();
                _transport.setContext(ctx);

                Connection connection = _amqpConnection.getConnection();

                Sasl sasl = _transport.sasl();
                sasl.client();

                _amqpConnection.setSasl(sasl);

                _transport.bind(connection);

                _collector = Collector.Factory.create();
                connection.collect(_collector);

                // XXX: really we should fire both of these off of an
                //      initial transport event
                write(ctx);
                scheduleReadIfCapacity(_transport, ctx);
            }
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, Object msg)
        {
            synchronized (_amqpConnection)
            {
                try
                {
                    ByteBuf buf = (ByteBuf) msg;

                    echoBytes("Got Bytes: ", buf);

                    try
                    {
                        while (buf.readableBytes() > 0)
                        {
                            int capacity = _transport.capacity();
                            if (capacity <= 0)
                            {
                                throw new IllegalStateException("discarding bytes: " + buf.readableBytes());
                            }
                            ByteBuffer tail = _transport.tail();
                            int min = Math.min(capacity, buf.readableBytes());
                            tail.limit(tail.position() + min);
                            buf.readBytes(tail);
                            _transport.process();
                            processAmqpConnection();
                            dispatch();

                            //TODO: make SASL layer event-friendly to remove need for this?
                            //We may have output pending but with no transport events to dispatch"
                            logMessage("Doing a write after processing AmqpConnection");
                            write(ctx);
                        }
                    }
                    finally
                    {
                        buf.release();
                    }

                    scheduleReadIfCapacity(_transport, ctx);
                }
                finally
                {
                    _amqpConnection.notifyAll();
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx)
        {
            synchronized (_amqpConnection)
            {
                logMessage("CHANNEL CLOSED");
                _transport.close_tail();
                dispatch();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
        {
            logMessage("Exception caught: " + cause.getMessage());
            cause.printStackTrace();
            closeOnFlush(ctx.channel());
        }

        void closeOnFlush(Channel ch)
        {
            if (ch.isActive())
            {
                ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        }

        private void dispatch()
        {
            logMessage("Dispatch called");
            synchronized (_amqpConnection)
            {
                try
                {
                    if (dispatching)
                    {
                        logMessage("Dispatch skipped");
                        return;
                    }
                    logMessage("Dispatching");
                    dispatching = true;
                    Event ev;
                    while ((ev = _collector.peek()) != null)
                    {
                        for (EventHandler h : handlers)
                        {
                            Events.dispatch(ev, h);
                        }
                        _collector.pop();
                    }

                    dispatching = false;
                }
                finally
                {
                    _amqpConnection.notifyAll();
                }
            }
        }


        private int offset = 0;

        private void write(final ChannelHandlerContext ctx)
        {
            logMessage("Write called");
            synchronized (_amqpConnection)
            {
                try
                {
                    logMessage("Checking pending");
                    int pending = _transport.pending();

                    logMessage("Pending:" + pending);
                    if (pending > 0)
                    {
                        final int size = pending - offset;
                        logMessage("Size:" + size);
                        if (size > 0)
                        {
                            ByteBuf buffer = Unpooled.buffer(size);
                            ByteBuffer head = _transport.head();
                            head.position(offset);
                            buffer.writeBytes(head);

                            echoBytes("Sending Bytes: ", buffer);

                            ChannelFuture chf = ctx.writeAndFlush(buffer);
                            offset += size;
                            chf.addListener(new ChannelFutureListener()
                            {
                                @Override
                                public void operationComplete(ChannelFuture chf)
                                {
                                    logMessage("Running write completion callback");
                                    if (chf.isSuccess())
                                    {
                                        synchronized (_amqpConnection)
                                        {
                                            _transport.pop(size);
                                            offset -= size;
                                        }
                                        write(ctx);//TODO: fix. Calling this can cause us to fire channelInactive before reading any responses, see below
                                        dispatch();
                                    }
                                    else
                                    {
                                        logMessage("Write operation FAILED");
                                    }
                                }
                            });
                            logMessage("Write completion callback added");
                        }
                        else
                        {
                            return;
                        }
                    }
                    else
                    {
                        if (pending < 0)
                        {
                            //TODO: fix. Calling this can cause us to fire channelInactive before reading any responses
                            //closeOnFlush(ctx.channel());
                        }
                        return;
                    }
                }
                finally
                {
                    _amqpConnection.notifyAll();
                }
            }
        }

        public void writePendingOutput()
        {
            write((ChannelHandlerContext)_transport.getContext());
        }

        private void scheduleReadIfCapacity(Transport transport, ChannelHandlerContext ctx)
        {
            logMessage("Checking if read can be scheduled");
            int capacity = transport.capacity();
            if (capacity > 0)
            {
                logMessage("Scheduling read");
                ctx.read();
                logMessage("Scheduled read");
            }
        }

        private void processAmqpConnection()
        {
            logMessage("Processing AmqpConnection");
            _amqpConnection.process();
        }
    }

    public void setLocallyUpdated(AmqpConnection amqpConnection)
    {
        //TODO: use the param or drop it
        _nettyHandler.writePendingOutput();
    }

    public void stop() throws InterruptedException
    {
        // TODO perhaps close the context?
    }

    private void logMessage(String message)
    {
        //TODO: delete this method
        if(LOGGER.isLoggable(Level.FINEST))
        {
            String name = Thread.currentThread().getName();
            LOGGER.finest("[" + name + "] " + message);
        }
    }

    private void echoBytes(String msgPrefix, ByteBuf buf)
    {
        //TODO: delete this method
        if(LOGGER_BYTES.isLoggable(Level.FINEST))
        {
            ByteBuffer nio = buf.nioBuffer();
            byte[] bytes = new byte[nio.limit()];
            nio.get(bytes);

            String name = Thread.currentThread().getName();
            LOGGER_BYTES.finest("[" + name + "] " + msgPrefix + new Binary(bytes));
        }
    }
}