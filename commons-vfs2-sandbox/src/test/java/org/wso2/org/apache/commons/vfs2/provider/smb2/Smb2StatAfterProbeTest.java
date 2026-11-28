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
package org.wso2.org.apache.commons.vfs2.provider.smb2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FilesCache;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.cache.DefaultFilesCache;
import org.wso2.org.apache.commons.vfs2.cache.NullFilesCache;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.StatNow;
import org.wso2.org.apache.commons.vfs2.provider.VfsComponentContext;

import com.hierynomus.msdtyp.FileTime;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileBasicInformation;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;

/**
 * The stat of the fail store after a probe. With no files cache it takes the record that the probe of the resolve
 * read, so the source is not asked twice. Every other object asks the server as statNow does.
 */
public class Smb2StatAfterProbeTest {

    private static final String PATH = "in\\f.dat";

    private Smb2Doubles.Client client;

    private Smb2FileSystem fileSystem;

    @BeforeEach
    public void open() throws Exception {
        client = new Smb2Doubles.Client();
        fileSystem = Smb2Doubles.fileSystem(new Smb2Doubles.Factory(client.wrapper, new Smb2Doubles.Client().wrapper));
    }

    private void manager(final FilesCache cache) throws FileSystemException {
        final DefaultFileSystemManager manager = new DefaultFileSystemManager();
        manager.setFilesCache(cache);
        final VfsComponentContext context = mock(VfsComponentContext.class);
        when(context.getFileSystemManager()).thenReturn(manager);
        fileSystem.setContext(context);
    }

    private Smb2FileObject file() throws FileSystemException {
        return new Smb2FileObject(Smb2Doubles.name("in/f.dat"), fileSystem, Smb2Doubles.rootName());
    }

    private static FileAllInformation record(final long size, final long modified) {
        final FileAllInformation info = mock(FileAllInformation.class);
        final FileStandardInformation standard = mock(FileStandardInformation.class);
        when(standard.isDirectory()).thenReturn(false);
        when(standard.getEndOfFile()).thenReturn(size);
        final FileBasicInformation basic = mock(FileBasicInformation.class);
        when(basic.getLastWriteTime()).thenReturn(FileTime.ofEpochMillis(modified));
        when(info.getStandardInformation()).thenReturn(standard);
        when(info.getBasicInformation()).thenReturn(basic);
        return info;
    }

    // The metadata opens of the path: one per query of the server.
    private void queries(final int count) {
        verify(client.share, times(count)).open(eq(PATH), eq(Smb2Doubles.METADATA), isNull(),
                eq(SMB2ShareAccess.ALL), eq(SMB2CreateDisposition.FILE_OPEN), isNull());
    }

    @Test
    public void aProbedObjectTakesTheRecordOfItsProbe() throws Exception {
        manager(new NullFilesCache());
        client.info(record(42, 1700000000000L));
        final Smb2FileObject probed = file();
        assertTrue(probed.exists());
        final StatNow stat = probed.statAfterProbe();
        assertTrue(stat.isThere());
        assertEquals(42, stat.getSize());
        assertEquals(1700000000000L, stat.getLastModifiedMillis());
        queries(1);
    }

    @Test
    public void anObjectThatNoProbeAttachedAsksTheServer() throws Exception {
        manager(new NullFilesCache());
        client.info(record(42, 1700000000000L));
        final StatNow stat = file().statAfterProbe();
        assertTrue(stat.isThere());
        assertEquals(42, stat.getSize());
        queries(1);
    }

    @Test
    public void aMissingNameIsNotThereByTheProbe() throws Exception {
        manager(new NullFilesCache());
        client.metadataOpenFails(new SMBApiException(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.getValue(),
                SMB2MessageCommandCode.SMB2_CREATE, "missing", null));
        final Smb2FileObject probed = file();
        assertFalse(probed.exists());
        assertFalse(probed.statAfterProbe().isThere());
        queries(1);
    }

    @Test
    public void aDetachedObjectAsksTheServer() throws Exception {
        manager(new NullFilesCache());
        client.info(record(42, 1700000000000L));
        final Smb2FileObject probed = file();
        assertTrue(probed.exists());
        probed.refresh();
        client.info(record(43, 1700000001000L));
        assertEquals(43, probed.statAfterProbe().getSize());
        queries(2);
    }

    @Test
    public void anObjectThatAFilesCacheMayKeepAsksTheServer() throws Exception {
        manager(new DefaultFilesCache());
        client.info(record(42, 1700000000000L));
        final Smb2FileObject probed = file();
        assertTrue(probed.exists());
        client.info(record(43, 1700000001000L));
        assertEquals(43, probed.statAfterProbe().getSize());
        queries(2);
    }
}
