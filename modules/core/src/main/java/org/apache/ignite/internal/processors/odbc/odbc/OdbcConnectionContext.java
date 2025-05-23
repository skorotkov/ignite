/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.odbc.odbc;

import java.util.HashSet;
import java.util.Set;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.configuration.QueryEngineConfiguration;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.binary.BinaryReaderEx;
import org.apache.ignite.internal.processors.odbc.ClientListenerAbstractConnectionContext;
import org.apache.ignite.internal.processors.odbc.ClientListenerMessageParser;
import org.apache.ignite.internal.processors.odbc.ClientListenerProtocolVersion;
import org.apache.ignite.internal.processors.odbc.ClientListenerRequestHandler;
import org.apache.ignite.internal.processors.odbc.ClientListenerResponse;
import org.apache.ignite.internal.processors.odbc.ClientListenerResponseSender;
import org.apache.ignite.internal.processors.platform.client.tx.ClientTxContext;
import org.apache.ignite.internal.processors.query.QueryEngineConfigurationEx;
import org.apache.ignite.internal.util.GridSpinBusyLock;
import org.apache.ignite.internal.util.nio.GridNioSession;

import static org.apache.ignite.internal.processors.odbc.ClientListenerNioListener.ODBC_CLIENT;

/**
 * ODBC Connection Context.
 */
public class OdbcConnectionContext extends ClientListenerAbstractConnectionContext {
    /** Version 2.1.0. */
    public static final ClientListenerProtocolVersion VER_2_1_0 = ClientListenerProtocolVersion.create(2, 1, 0);

    /** Version 2.1.5: added "lazy" flag. */
    public static final ClientListenerProtocolVersion VER_2_1_5 = ClientListenerProtocolVersion.create(2, 1, 5);

    /** Version 2.3.0: added "skipReducerOnUpdate" flag. */
    public static final ClientListenerProtocolVersion VER_2_3_0 = ClientListenerProtocolVersion.create(2, 3, 0);

    /** Version 2.3.2: added multiple statements support. */
    public static final ClientListenerProtocolVersion VER_2_3_2 = ClientListenerProtocolVersion.create(2, 3, 2);

    /** Version 2.5.0: added authentication. */
    public static final ClientListenerProtocolVersion VER_2_5_0 = ClientListenerProtocolVersion.create(2, 5, 0);

    /** Version 2.7.0: added precision and scale. */
    public static final ClientListenerProtocolVersion VER_2_7_0 = ClientListenerProtocolVersion.create(2, 7, 0);

    /** Version 2.8.0: added column nullability info. */
    public static final ClientListenerProtocolVersion VER_2_8_0 = ClientListenerProtocolVersion.create(2, 8, 0);

    /** Version 2.13.0: added ability to choose of query engine support. */
    public static final ClientListenerProtocolVersion VER_2_13_0 = ClientListenerProtocolVersion.create(2, 13, 0);

    /** Current version. */
    private static final ClientListenerProtocolVersion CURRENT_VER = VER_2_13_0;

    /** Supported versions. */
    private static final Set<ClientListenerProtocolVersion> SUPPORTED_VERS = new HashSet<>();

    /** Default nested tx mode for compatibility. */
    private static final byte DEFAULT_NESTED_TX_MODE = 3;

    /** Shutdown busy lock. */
    private final GridSpinBusyLock busyLock;

    /** Maximum allowed cursors. */
    private final int maxCursors;

    /** Message parser. */
    private OdbcMessageParser parser;

    /** Request handler. */
    private OdbcRequestHandler handler;

    /** Logger. */
    private final IgniteLogger log;

    static {
        SUPPORTED_VERS.add(CURRENT_VER);
        SUPPORTED_VERS.add(VER_2_8_0);
        SUPPORTED_VERS.add(VER_2_7_0);
        SUPPORTED_VERS.add(VER_2_5_0);
        SUPPORTED_VERS.add(VER_2_3_0);
        SUPPORTED_VERS.add(VER_2_3_2);
        SUPPORTED_VERS.add(VER_2_1_5);
        SUPPORTED_VERS.add(VER_2_1_0);
    }

    /**
     * Constructor.
     * @param ctx Kernal Context.
     * @param ses Client's NIO session.
     * @param busyLock Shutdown busy lock.
     * @param connId Connection ID.
     * @param maxCursors Maximum allowed cursors.
     */
    public OdbcConnectionContext(GridKernalContext ctx, GridNioSession ses, GridSpinBusyLock busyLock, long connId, int maxCursors) {
        super(ctx, ses, connId);

        this.busyLock = busyLock;
        this.maxCursors = maxCursors;

        log = ctx.log(getClass());
    }

    /** {@inheritDoc} */
    @Override public byte clientType() {
        return ODBC_CLIENT;
    }

    /** {@inheritDoc} */
    @Override public boolean isVersionSupported(ClientListenerProtocolVersion ver) {
        return SUPPORTED_VERS.contains(ver);
    }

    /** {@inheritDoc} */
    @Override public ClientListenerProtocolVersion defaultVersion() {
        return CURRENT_VER;
    }

    /** {@inheritDoc} */
    @Override public void initializeFromHandshake(GridNioSession ses,
        ClientListenerProtocolVersion ver, BinaryReaderEx reader)
        throws IgniteCheckedException {
        assert SUPPORTED_VERS.contains(ver) : "Unsupported ODBC protocol version.";

        boolean distributedJoins = reader.readBoolean();
        boolean enforceJoinOrder = reader.readBoolean();
        boolean replicatedOnly = reader.readBoolean();
        boolean collocated = reader.readBoolean();

        boolean lazy = false;

        if (ver.compareTo(VER_2_1_5) >= 0)
            lazy = reader.readBoolean();

        boolean skipReducerOnUpdate = false;

        if (ver.compareTo(VER_2_3_0) >= 0)
            skipReducerOnUpdate = reader.readBoolean();

        String user = null;
        String passwd = null;

        if (ver.compareTo(VER_2_5_0) >= 0) {
            user = reader.readString();
            passwd = reader.readString();
        }

        if (ver.compareTo(VER_2_7_0) >= 0 && reader.readByte() != DEFAULT_NESTED_TX_MODE)
            throw new IgniteCheckedException("Nested transactions are not supported!");

        String qryEngine = null;
        if (ver.compareTo(VER_2_13_0) >= 0) {
            qryEngine = reader.readString();

            if (qryEngine != null) {
                QueryEngineConfiguration[] cfgs = ctx.config().getSqlConfiguration().getQueryEnginesConfiguration();

                boolean found = false;

                if (cfgs != null) {
                    for (int i = 0; i < cfgs.length; i++) {
                        if (qryEngine.equalsIgnoreCase(((QueryEngineConfigurationEx)cfgs[i]).engineName())) {
                            found = true;
                            break;
                        }
                    }
                }

                if (!found)
                    throw new IgniteCheckedException("Not found configuration for query engine: " + qryEngine);
            }
        }

        authenticate(ses, user, passwd);

        ClientListenerResponseSender snd = new ClientListenerResponseSender() {
            @Override public void send(ClientListenerResponse resp) {
                if (resp != null) {
                    if (log.isDebugEnabled())
                        log.debug("Async response: [resp=" + resp.status() + ']');

                    ses.send(parser.encode(resp));
                }
            }
        };

        initClientDescriptor("odbc");

        handler = new OdbcRequestHandler(ctx, busyLock, snd, maxCursors, distributedJoins, enforceJoinOrder,
            replicatedOnly, collocated, lazy, skipReducerOnUpdate, qryEngine, ver, this);

        parser = new OdbcMessageParser(ctx, ver);

        handler.start();
    }

    /** {@inheritDoc} */
    @Override public ClientListenerRequestHandler handler() {
        return handler;
    }

    /** {@inheritDoc} */
    @Override public ClientListenerMessageParser parser() {
        return parser;
    }

    /** {@inheritDoc} */
    @Override public void onDisconnected() {
        handler.onDisconnect();

        super.onDisconnected();
    }

    /** {@inheritDoc} */
    @Override public ClientTxContext txContext(int txId) {
        throw new UnsupportedOperationException();
    }

    /** {@inheritDoc} */
    @Override public void addTxContext(ClientTxContext txCtx) {
        throw new UnsupportedOperationException();
    }

    /** {@inheritDoc} */
    @Override public void removeTxContext(int txId) {
        throw new UnsupportedOperationException();
    }

    /** {@inheritDoc} */
    @Override protected void cleanupTxs() {
        throw new UnsupportedOperationException();
    }
}
