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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.cache.LRUFilesCache;
import org.wso2.org.apache.commons.vfs2.cache.NullFilesCache;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;

/**
 * The children of an FTP folder take their entry from the one listing of that folder, so asking each child its type
 * lists the folder no more. The manager has no files cache, as the inbound file endpoint, so each new parent object
 * would list the folder again.
 */
public class FtpListedChildrenTest {

    private static final int FILES = 50;

    private static final byte[] CONTENT = "abc".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    File home;

    private ScriptedFtpServer server;

    private StandardFileSystemManager manager;

    @BeforeEach
    public void start() throws Exception {
        final File in = new File(home, "in");
        in.mkdir();
        for (int i = 0; i < FILES; i++) {
            Files.write(new File(in, String.format("f%02d.csv", i)).toPath(), CONTENT);
        }
        final File ln = new File(home, "ln");
        ln.mkdir();
        Files.write(new File(ln, "a.csv").toPath(), CONTENT);
        server = ScriptedFtpServer.plain(home);
        manager = new StandardFileSystemManager();
        manager.setFilesCache(new NullFilesCache());
        manager.init();
    }

    @AfterEach
    public void stop() {
        manager.close();
        server.close();
    }

    private FileObject resolve(final String path) throws FileSystemException {
        return manager.resolveFile(server.uri("ftp", path));
    }

    // The listings after a mark of the trace, with no session number.
    private List<String> listings(final int mark) {
        final List<String> all = server.trace();
        return all.subList(mark, all.size()).stream().map(line -> line.substring(line.indexOf(' ') + 1))
                .filter(line -> line.startsWith("LIST")).collect(Collectors.toList());
    }

    @Test
    public void typeOfEachListedChildComesFromTheOneListing() throws Exception {
        final FileObject folder = resolve("/in");
        assertEquals(FileType.FOLDER, folder.getType());
        final int mark = server.trace().size();
        final FileObject[] children = folder.getChildren();
        assertEquals(FILES, children.length);
        for (final FileObject child : children) {
            assertEquals(FileType.FILE, child.getType(), child.getName().getBaseName());
            assertEquals(CONTENT.length, child.getContent().getSize(), child.getName().getBaseName());
        }
        assertEquals(1, listings(mark).size(), String.valueOf(listings(mark)));
        assertEquals("LIST -a in", listings(mark).get(0));
    }

    // With a files cache a child that the cache keeps takes no entry. A child that nobody asked before the close of
    // its folder lists the folder anew, as before.
    @Test
    public void withAFilesCacheAChildAfterTheCloseOfItsFolderListsAnew() throws Exception {
        manager.close();
        manager = new StandardFileSystemManager();
        manager.setFilesCache(new LRUFilesCache(1000));
        manager.init();
        final FileObject folder = resolve("/in");
        final FileObject[] children = folder.getChildren();
        assertEquals(FileType.FILE, children[0].getType());
        folder.close();
        final int mark = server.trace().size();
        assertEquals(FileType.FILE, children[1].getType());
        assertEquals(1, listings(mark).size(), String.valueOf(listings(mark)));
    }

    @Test
    public void parentOfEachListedChildIsTheListedFolder() throws Exception {
        final FileObject folder = resolve("/in");
        for (final FileObject child : folder.getChildren()) {
            assertEquals(folder.getName(), child.getName().getParent());
            assertEquals(folder.getName(), child.getParent().getName());
        }
    }

    @Test
    public void linkInTheListingIsReadAsToday() throws Exception {
        // The server never lists a link, so the listing of the folder is scripted. b.csv is a link to a.csv.
        server.listing("ln", 10, "-rw-r--r--   1 user group        3 Nov 28 10:00 a.csv",
                "lrwxrwxrwx   1 user group        5 Nov 28 10:00 b.csv -> a.csv");
        final FileObject folder = resolve("/ln");
        assertEquals(FileType.FOLDER, folder.getType());
        final FileObject[] children = folder.getChildren();
        assertEquals(2, children.length);
        final FileObject link = children[1];
        assertEquals("b.csv", link.getName().getBaseName());
        final int mark = server.trace().size();
        // As today, the link finds its entry in a listing of its folder, and its target finds its own entry in another.
        assertEquals(FileType.FILE, link.getType());
        assertEquals(CONTENT.length, link.getContent().getSize());
        assertEquals(2, listings(mark).size(), String.valueOf(listings(mark)));
    }
}
