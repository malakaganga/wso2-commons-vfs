/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.org.apache.commons.vfs2.provider.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.ACQUIRED;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.BUSY_SHARE;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.cache.LRUFilesCache;
import org.wso2.org.apache.commons.vfs2.cache.NullFilesCache;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.vfs.shared.failstate.FailStateStore;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.LockOutcome;
import org.wso2.vfs.shared.lock.ShareLock;

import com.jcraft.jsch.Session;

/**
 * A lock take and a look of the fail store ask the server through one view. Its first resolve probes the name and so
 * checks the connection, the later ones resolve with no probe. Each name of the step is asked once.
 */
class LockViewSftpTest {

    private static final String START = "/home/" + SftpTestServer.USER;

    private final String endpoint = "view" + UUID.randomUUID();

    private final EndpointHealth health = HealthRegistry.register(HealthRegistry.Kind.INBOUND, endpoint, 5000);

    private final ShareLock locks = ShareLock.create(endpoint, true, 900000, health);

    private SftpTestServer server;

    private StandardFileSystemManager manager;

    private FileSystemOptions options;

    @BeforeEach
    void start() throws Exception {
        server = SftpTestServer.startingIn(START);
        manager = new StandardFileSystemManager();
        manager.setFilesCache(new NullFilesCache());
        manager.init();
        options = SftpTestServer.options();
        SftpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(options, false);
    }

    @AfterEach
    void stop() throws Exception {
        HealthRegistry.unregister(HealthRegistry.Kind.INBOUND, endpoint);
        manager.close();
        server.close();
    }

    // The listener take on a server with an exclusive create: the capability is asked on the data name, then the
    // check and the look of the data name, then the create. The lock name is asked once before its link.
    @Test
    void aTakeAsksTheLockNameOnceBeforeTheCreate() throws Exception {
        server.write("data/f.txt", "f");
        final LockIo io = new LockIo(manager, options);
        server.clearNamed();
        assertEquals(ACQUIRED, locks.acquire(io, server.uri("data/f.txt"), ShareLock.Mode.LISTENER, null).kind);
        final List<String> named = server.named();
        final int link = named.indexOf("LINK /data/f.txt.lock");
        assertTrue(link > 0, named.toString());
        assertEquals(1, looks(named.subList(0, link), "/data/f.txt.lock"), named.toString());
    }

    // The fail store looks at the marker of the file once. The stat of the file before it probes the connection.
    @Test
    void theFailStoreAsksTheMarkerNameOnce() throws Exception {
        server.write("data/f.txt", "f");
        final String data = server.uri("data/f.txt");
        final LockIo io = new LockIo(manager, options);
        final LockOutcome taken = locks.acquire(io, data, ShareLock.Mode.LISTENER, null);
        assertEquals(ACQUIRED, taken.kind);
        final FailStateStore store = FailStateStore.forEndpoint(endpoint, new FailStateStore.Parameters(), locks,
                health);
        server.clearNamed();
        assertEquals(FailStateStore.Decision.PROCESS, store.beginFile(io, taken.handle, data).decision);
        final List<String> named = server.named();
        assertEquals(1, looks(named, "/data/f.txt.fail"), named.toString());
    }

    // Each name a view resolves with no probe is the name a resolve of the manager gives for the same address.
    @Test
    void theViewResolvesTheNamesOfTheManager() throws Exception {
        server.write("data/f.txt", "f");
        final LockIo view = new LockIo(manager, options).view();
        view.resolve(server.uri("data/f.txt")).close();
        for (final String name : new String[] {"a b.txt", "a%41.txt", "a#b.txt", "a?b.txt", "f.txt?x=1",
                "f.txt.lock?x=1&y=2", ":x.txt", "a\\b.txt"}) {
            final String uri = server.uri("data/" + name);
            final FileName probed = nameOrNull(() -> manager.resolveFile(uri, options));
            final FileName viewed = nameOrNull(() -> view.resolve(uri));
            if (probed == null) {
                assertNull(viewed, name);
                continue;
            }
            assertEquals(probed.getURI(), viewed.getURI(), name);
            assertEquals(probed.getPath(), viewed.getPath(), name);
        }
    }

    // A server with no exclusive create still gets its WARN at the first take, which now probes the data name.
    @Test
    void theFirstTakeWarnsOfAServerWithNoExclusiveCreate() throws Exception {
        try (SftpTestServer plain = new SftpTestServer()) {
            plain.write("data/f.txt", "f");
            plain.write("data/g.txt", "g");
            final List<String> warnings = new ArrayList<>();
            final Handler capture = new Handler() {
                @Override
                public void publish(final LogRecord record) {
                    if (record.getMessage().contains("The server has no exclusive lock create.")) {
                        warnings.add(record.getMessage());
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            final Logger logger = Logger.getLogger(EndpointHealth.class.getName());
            logger.addHandler(capture);
            try {
                final LockIo io = new LockIo(manager, options);
                assertEquals(ACQUIRED, locks.acquire(io, plain.uri("data/f.txt"), ShareLock.Mode.LISTENER, null).kind);
                assertEquals(ACQUIRED, locks.acquire(io, plain.uri("data/g.txt"), ShareLock.Mode.LISTENER, null).kind);
            } finally {
                logger.removeHandler(capture);
            }
            assertEquals(1, warnings.size());
        }
    }

    // A session that dropped while the endpoint was idle is connected again by the probe of the next take.
    @Test
    void aSessionDroppedWhileIdleIsConnectedAgainAtTheFirstResolve() throws Exception {
        server.write("data/f.txt", "f");
        server.write("data/g.txt", "g");
        final LockIo io = new LockIo(manager, options);
        assertEquals(ACQUIRED, locks.acquire(io, server.uri("data/f.txt"), ShareLock.Mode.LISTENER, null).kind);
        final SftpFileSystem fileSystem = (SftpFileSystem) io.resolve(server.uri("data")).getFileSystem();
        final Session dropped = SftpTestServer.sessionOf(fileSystem);
        dropped.disconnect();
        assertEquals(ACQUIRED, locks.acquire(io, server.uri("data/g.txt"), ShareLock.Mode.LISTENER, null).kind);
        assertNotSame(dropped, SftpTestServer.sessionOf(fileSystem));
        assertTrue(Files.exists(server.getRoot().resolve("data/g.txt.lock")));
    }

    // Another task closes the file system after the first probe of the take, as a probe that met a dead connection
    // does. The later calls of the view fail on the closed file system. The take ends with an error and leaves no
    // lock, never with a free lock. A closed file system is not a reply of the server, so the error is not a
    // transport error. The file waits for the next poll.
    @Test
    void aFileSystemClosedAfterTheFirstProbeEndsTheTakeWithAnError() throws Exception {
        server.write("data/f.txt", "f");
        final LockIo io = new LockIo(manager, options) {
            @Override
            public LockIo view() {
                final LockIo view = super.view();
                return new LockIo(manager, options) {
                    private boolean probed;

                    @Override
                    public FileObject resolve(final String uri) throws FileSystemException {
                        final FileObject file = view.resolve(uri);
                        if (!probed) {
                            probed = true;
                            manager.closeFileSystem(file.getFileSystem());
                        }
                        return file;
                    }
                };
            }
        };
        final LockOutcome taken = locks.acquire(io, server.uri("data/f.txt"), ShareLock.Mode.LISTENER, null);
        assertEquals(LockOutcome.Kind.ERROR, taken.kind);
        assertFalse(Files.exists(server.getRoot().resolve("data/f.txt.lock")));
    }

    // The connector write with the files cache on: the name of the file and of its lock come from the cache of the
    // file system, and each look still asks the server. A lock another party wrote between two takes is seen.
    @Test
    void aWriterTakeWithTheFilesCacheSeesEachChangeOfTheLock() throws Exception {
        manager.close();
        manager = new StandardFileSystemManager();
        manager.setFilesCache(new LRUFilesCache(100));
        manager.init();
        Files.createDirectories(server.getRoot().resolve("data"));
        final String data = server.uri("data/new.txt");
        final LockIo io = new LockIo(manager, options);
        final LockOutcome first = locks.acquire(io, data, ShareLock.Mode.WRITER, null);
        assertEquals(ACQUIRED, first.kind);
        assertEquals(LockOutcome.Kind.RELEASED, first.handle.releaseChecked(io).kind);
        assertFalse(Files.exists(server.getRoot().resolve("data/new.txt.lock")));
        server.write("data/new.txt.lock", "another party");
        assertEquals(BUSY_SHARE, locks.acquire(io, data, ShareLock.Mode.WRITER, null).kind);
        Files.delete(server.getRoot().resolve("data/new.txt.lock"));
        assertEquals(ACQUIRED, locks.acquire(io, data, ShareLock.Mode.WRITER, null).kind);
        assertTrue(Files.exists(server.getRoot().resolve("data/new.txt.lock")));
    }

    // The stats and lstats of a path.
    private static long looks(final List<String> named, final String path) {
        return named.stream().filter(request -> request.equals("STAT " + path) || request.equals("LSTAT " + path))
                .count();
    }

    private interface Resolve {
        FileObject get() throws FileSystemException;
    }

    // The name of the resolved object, or null when the address can not be resolved.
    private static FileName nameOrNull(final Resolve resolve) {
        try {
            return resolve.get().getName();
        } catch (final FileSystemException refused) {
            return null;
        }
    }
}
