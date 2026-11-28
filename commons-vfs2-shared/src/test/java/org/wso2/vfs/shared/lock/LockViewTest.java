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
package org.wso2.vfs.shared.lock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.ACQUIRED;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.cache.NullFilesCache;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;

/** The view of one lock take on local files: its names and a take over of a stale lock through it. */
class LockViewTest {

    @TempDir
    Path folder;

    private final String endpoint = "view" + UUID.randomUUID();

    private final EndpointHealth health = HealthRegistry.register(HealthRegistry.Kind.INBOUND, endpoint, 5000);

    private final ShareLock locks = ShareLock.create(endpoint, true, 900000, health);

    private StandardFileSystemManager manager;

    @BeforeEach
    void start() throws Exception {
        manager = new StandardFileSystemManager();
        manager.setFilesCache(new NullFilesCache());
        manager.init();
    }

    @AfterEach
    void stop() {
        HealthRegistry.unregister(HealthRegistry.Kind.INBOUND, endpoint);
        manager.close();
    }

    // Each name a view resolves with no probe is the name a resolve of the manager gives for the same address. A cut
    // or changed name could be a real file.
    @Test
    void theViewResolvesTheNamesOfTheManager() throws Exception {
        final String base = folder.toUri().toString();
        final LockIo io = new LockIo(manager, null);
        final LockIo view = io.view();
        view.resolve(base + "f.dat").close();
        for (final String name : new String[] {"a?b.dat", "a#b.dat", "a%41.dat", "a\\b.dat", "file:1.dat", "a b.dat",
                ":x.dat", "f.dat.lock", "f.dat.fail"}) {
            final String uri = base + name;
            final FileName probed = nameOrNull(() -> io.resolve(uri));
            final FileName viewed = nameOrNull(() -> view.resolve(uri));
            if (probed == null) {
                assertNull(viewed, name);
                continue;
            }
            assertEquals(probed.getURI(), viewed.getURI(), name);
            assertEquals(probed.getPath(), viewed.getPath(), name);
        }
    }

    // A stale lock of a crashed node is taken over inside the check of the take. The take away, the read, the
    // delete and the create all go through the view of the take.
    @Test
    void aStaleLockIsTakenOverThroughTheView() throws Exception {
        final Path data = Files.write(folder.resolve("f.dat"), new byte[] {1});
        final Path lock = folder.resolve("f.dat.lock");
        Files.write(lock, LockValue.create(1, UUID.randomUUID().toString()).bytes);
        final LockIo io = new LockIo(manager, null);
        final LockOutcome taken = locks.acquire(io, data.toUri().toString(), ShareLock.Mode.LISTENER, null);
        assertEquals(ACQUIRED, taken.kind);
        assertTrue(taken.handle.tookOver());
        assertArrayEquals(taken.handle.entry.value.bytes, Files.readAllBytes(lock));
        try (Stream<Path> files = Files.list(folder)) {
            assertEquals(2, files.count());
        }
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
