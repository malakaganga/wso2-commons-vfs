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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.cache.NullFilesCache;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.ftp.ScriptedFtpServer;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;

/** The lock write of the weak rule on FTP, which has no exclusive create. */
class LockViewFtpTest {

    @TempDir
    File home;

    private final String endpoint = "ftpview" + UUID.randomUUID();

    private final EndpointHealth health = HealthRegistry.register(HealthRegistry.Kind.INBOUND, endpoint, 5000);

    private final ShareLock locks = ShareLock.create(endpoint, true, 900000, health);

    private ScriptedFtpServer server;

    private StandardFileSystemManager manager;

    private Path data;

    @BeforeEach
    void start() throws Exception {
        data = Files.write(Files.createDirectories(home.toPath().resolve("in")).resolve("f.dat"),
                "f".getBytes(StandardCharsets.UTF_8));
        server = ScriptedFtpServer.plain(home);
        manager = new StandardFileSystemManager();
        manager.setFilesCache(new NullFilesCache());
        manager.init();
    }

    @AfterEach
    void stop() {
        HealthRegistry.unregister(HealthRegistry.Kind.INBOUND, endpoint);
        manager.close();
        server.close();
    }

    // The write of a lock in a known folder writes the whole content and replaces an older one, as the plain write.
    @Test
    void aWriteInAKnownFolderWritesTheWholeContent() throws Exception {
        final Path lock = home.toPath().resolve("in/f.dat.lock");
        try (FileObject file = manager.resolveFile(server.uri("ftp", "/in/f.dat.lock"), new FileSystemOptions())) {
            AtomicOps.of(file).writeInKnownFolder("a longer first value".getBytes(StandardCharsets.UTF_8));
        }
        assertArrayEquals("a longer first value".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(lock));
        try (FileObject file = manager.resolveFile(server.uri("ftp", "/in/f.dat.lock"), new FileSystemOptions())) {
            AtomicOps.of(file).writeInKnownFolder("second".getBytes(StandardCharsets.UTF_8));
        }
        assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(lock));
    }
}
