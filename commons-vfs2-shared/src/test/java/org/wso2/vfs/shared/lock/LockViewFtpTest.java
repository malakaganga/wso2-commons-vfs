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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.ACQUIRED;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.ERROR;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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

/**
 * The weak lock rule on FTP, which has no exclusive create: write the lock, read it back, wait, read it back again. A
 * listener writes its lock with no look at the folder above.
 */
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

    // A take of the listener: the listing of the poll holds the file, as the inbound gives it.
    private LockOutcome take() throws Exception {
        final FolderListing listing = FolderListing.of(server.uri("ftp", "/in"), Collections.singletonMap("f.dat",
                new FolderListing.Metadata(Files.size(data), Files.getLastModifiedTime(data).toMillis())));
        return locks.acquire(new LockIo(manager, new FileSystemOptions()), server.uri("ftp", "/in/f.dat"),
                ShareLock.Mode.LISTENER, listing);
    }

    // The commands of the take with no login and no PORT.
    private List<String> commands() {
        return server.trace().stream().map(line -> line.substring(line.indexOf(' ') + 1))
                .filter(command -> !command.startsWith("PORT") && !command.startsWith("USER")
                        && !command.startsWith("PASS") && !command.startsWith("TYPE") && !command.startsWith("SYST")
                        && !command.startsWith("FEAT"))
                .collect(Collectors.toList());
    }

    // The weak take of a listener lists the folder of the file six times: the probe of the data name, the look of
    // the lock name, the probe of the write, the listing after the STOR and the probe of each read back. The folder
    // above is not listed.
    @Test
    void aWeakTakeOfAListenerDoesNotListTheFolderAbove() throws Exception {
        final LockOutcome taken = take();
        assertEquals(ACQUIRED, taken.kind, server.trace().toString());
        assertArrayEquals(taken.handle.entry.value.bytes, Files.readAllBytes(home.toPath().resolve("in/f.dat.lock")));
        assertEquals(Arrays.asList("LIST -a in", "MDTM in/f.dat.lock", "LIST -a in", "LIST -a in",
                "STOR in/f.dat.lock", "LIST -a in", "LIST -a in", "RETR in/f.dat.lock", "LIST -a in",
                "RETR in/f.dat.lock"), commands());
    }

    // The server refuses the STOR of the lock, as it does when the folder is gone. The take ends with an error and a
    // line for the operator, and no lock is left.
    @Test
    void aRefusedLockWriteEndsTheTakeWithALine() throws Exception {
        final List<String> lines = new ArrayList<>();
        final Handler capture = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                if (record.getMessage().contains("could not be taken")) {
                    lines.add(record.getMessage());
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
            server.reply("STOR", null, 550, 1);
            assertEquals(ERROR, take().kind, server.trace().toString());
        } finally {
            logger.removeHandler(capture);
        }
        assertEquals(1, lines.size());
        assertFalse(Files.exists(home.toPath().resolve("in/f.dat.lock")));
        assertEquals(0, server.count("MKD"), server.trace().toString());
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
