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
package org.wso2.org.apache.commons.vfs2.provider.ftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.cache.NullFilesCache;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;

/**
 * The write of a lock file in a folder that the caller knows to be there. On FTP it is the plain write with no listing
 * of the folder above. The look at the lock name before the STOR and the listing after it stay. The manager has no
 * files cache, as the inbound file endpoint.
 */
public class FtpKnownFolderWriteTest {

    private static final byte[] LOCK = "owner one 1764300000000".getBytes(StandardCharsets.US_ASCII);

    // The listing of the folder above the lock folder a/in.
    private static final String FOLDER_ABOVE = "LIST -a a";

    @TempDir
    File home;

    private ScriptedFtpServer server;

    private StandardFileSystemManager manager;

    @BeforeEach
    public void start() throws Exception {
        new File(home, "a/in").mkdirs();
        Files.write(new File(home, "a/in/data.csv").toPath(), LOCK);
        server = ScriptedFtpServer.plain(home);
        manager = new StandardFileSystemManager();
        manager.setFilesCache(new NullFilesCache());
        manager.init();
        // A first look opens the client and shows that the server takes LIST -a, so every write starts alike.
        assertEquals(FileType.FILE, resolve("/a/in/data.csv").getType());
    }

    @AfterEach
    public void stop() {
        manager.close();
        server.close();
    }

    private FileObject resolve(final String path) throws FileSystemException {
        return manager.resolveFile(server.uri("ftp", path));
    }

    // The commands after a mark of the trace, with no session number. A PORT keeps no argument, since its port
    // changes with each data connection.
    private List<String> since(final int mark) {
        final List<String> all = server.trace();
        return all.subList(mark, all.size()).stream().map(line -> line.substring(line.indexOf(' ') + 1))
                .map(line -> line.startsWith("PORT ") ? "PORT" : line).collect(Collectors.toList());
    }

    // The lock file resolved below its folder with no probe of the name, as the lock code resolves it. So the write
    // itself makes the look at the name.
    private FileObject lock(final String name) throws FileSystemException {
        return resolve("/a/in").resolveFile(name);
    }

    private byte[] onServer(final String name) throws IOException {
        return Files.readAllBytes(new File(home, "a/in/" + name).toPath());
    }

    private static void plainWrite(final FileObject file, final byte[] content) throws IOException {
        try (OutputStream output = file.getContent().getOutputStream(false)) {
            output.write(content);
        }
    }

    @Test
    public void knownFolderWriteSendsNoListingOfTheFolderAbove() throws Exception {
        final FileObject lock = lock("k.lock");
        final int mark = server.trace().size();
        AtomicOps.of(lock).writeInKnownFolder(LOCK);
        assertFalse(since(mark).contains(FOLDER_ABOVE), String.valueOf(since(mark)));
        assertArrayEquals(LOCK, onServer("k.lock"));
    }

    @Test
    public void plainWriteSendsTheListingOfTheFolderAbove() throws Exception {
        final FileObject lock = lock("p.lock");
        final int mark = server.trace().size();
        plainWrite(lock, LOCK);
        assertTrue(since(mark).contains(FOLDER_ABOVE), String.valueOf(since(mark)));
        assertArrayEquals(LOCK, onServer("p.lock"));
    }

    @Test
    public void knownFolderWriteIsThePlainWriteLessTheListingOfTheFolderAbove() throws Exception {
        final FileObject plainLock = lock("p.lock");
        final FileObject knownLock = lock("k.lock");
        int mark = server.trace().size();
        plainWrite(plainLock, LOCK);
        final List<String> plain = since(mark);
        mark = server.trace().size();
        AtomicOps.of(knownLock).writeInKnownFolder(LOCK);
        final List<String> known = since(mark);
        // The look at the lock name before the STOR and the listing after it stay.
        assertEquals(Arrays.asList("PORT", "LIST -a a/in", "PORT", "STOR a/in/k.lock", "PORT", "LIST -a a/in"), known);

        final List<String> expected = new ArrayList<>();
        for (final String line : plain) {
            expected.add(line.replace("p.lock", "k.lock"));
        }
        // The listing of the folder above goes with the PORT of its data connection.
        final int above = expected.indexOf(FOLDER_ABOVE);
        assertTrue(above > 0 && "PORT".equals(expected.get(above - 1)), String.valueOf(plain));
        expected.subList(above - 1, above + 1).clear();
        assertEquals(expected, known, "plain " + plain);
        assertArrayEquals(onServer("p.lock"), onServer("k.lock"));
        assertArrayEquals(LOCK, onServer("k.lock"));
    }
}
