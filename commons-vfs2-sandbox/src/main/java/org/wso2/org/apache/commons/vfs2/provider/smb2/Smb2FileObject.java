/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.org.apache.commons.vfs2.provider.smb2;

import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.File;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileObject;
import org.wso2.org.apache.commons.vfs2.provider.UriParser;
import org.wso2.org.apache.commons.vfs2.util.MonitorInputStream;
import org.wso2.org.apache.commons.vfs2.util.MonitorOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class containing all its handles from the current Instance but NOT all
 * possible handles to the same file (read/write separated)
 */
public class Smb2FileObject extends AbstractFileObject<Smb2FileSystem> {

    private static final Log LOG = LogFactory.getLog(Smb2FileObject.class);
    private static final char[] RESERVED_FILE_CHARS = {' ', '#'};
    private final String relPathToShare;
    private FileAllInformation fileInfo;
    private FileName rootName;

    /**
     * @param name       the file name - muse be an instance of {@link AbstractFileName}
     * @param fileSystem the file system
     * @throws ClassCastException if {@code name} is not an instance of {@link AbstractFileName}
     */
    protected Smb2FileObject(AbstractFileName name, Smb2FileSystem fileSystem, final FileName rootName) {

        super(name, fileSystem);
        String relPath = name.getURI().substring(rootName.getURI().length());
        // smb shares do not accept "/" --> it needs a "\" which is represented by "\\"
        relPathToShare = relPath.startsWith("/") ? relPath.substring(1).replace("/", "\\") : relPath.replace("/", "\\");
        this.rootName = rootName;
    }

    @Override
    protected long doGetContentSize() throws Exception {

        getFileInfo();
        return fileInfo.getStandardInformation().getEndOfFile();
    }

    @Override
    protected InputStream doGetInputStream() throws Exception {

        if (!getType().hasContent()) {
            throw new FileSystemException("vfs.provider/read-not-file.error", getName());
        }
        return getDiskEntryReadInputStream();

    }

    @Override
    protected FileType doGetType() throws Exception {

        synchronized (getFileSystem()) {
            if (this.fileInfo == null) {
                // returns null if the diskShare cannot fin the file info's. Therefore : imaginary
                getFileInfo();
            }
            if (fileInfo == null) {
                return FileType.IMAGINARY;
            } else {
                return (fileInfo.getStandardInformation().isDirectory()) ? FileType.FOLDER : FileType.FILE;
            }
        }
    }

    @Override
    protected String[] doListChildren() throws Exception {

        // do nothing here since doListChildrenResolved has been implemented
        return null;
    }

    private void getFileInfo() throws FileSystemException {

        if (fileInfo == null) {
            synchronized (getFileSystem()) {
                Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
                Smb2ClientWrapper client = (Smb2ClientWrapper) fileSystem.getClient();
                Throwable failure = null;
                try {
                    fileInfo = client.getFileInfo(getRelPathToShare());
                } catch (FileSystemException | RuntimeException e) {
                    failure = e;
                    throw e;
                } finally {
                    fileSystem.giveBack(client, failure);
                }
            }
        }
    }

    @Override
    public FileObject getParent() throws FileSystemException {

        synchronized (getFileSystem()) {
            AbstractFileName name = (AbstractFileName) getName().getParent();

            // root folder has no parent
            if (name == null) {
                return null;
            }
            FileObject cachedFile = getFileSystem().getFileSystemManager().getFilesCache().getFile(getFileSystem(),
                    name);
            if (cachedFile != null) {
                return cachedFile;
            } else {
                return new Smb2FileObject(name, (Smb2FileSystem) getFileSystem(), rootName);
            }
        }
    }

    @Override
    protected OutputStream doGetOutputStream(final boolean bAppend) throws Exception {
        Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
        Smb2ClientWrapper smb2ClientWrapper = (Smb2ClientWrapper) fileSystem.getClient();
        // The handle is owned here until the stream takes it. For an append smbj asks the handle for the end of the
        // file, and a refusal there must not leave the handle open on the server.
        DiskEntry diskEntryWrite = null;
        try {
            synchronized (getFileSystem()) {
                diskEntryWrite = smb2ClientWrapper.getDiskEntryWrite(getRelPathToShare(), bAppend);
                OutputStream os = ((File) diskEntryWrite).getOutputStream(bAppend);
                return new Smb2OutputStream(smb2ClientWrapper, os, diskEntryWrite);
            }
        } catch (Exception e) {
            FileSystemException failure = failure(smb2ClientWrapper, "vfs.provider.smb2/diskentry-create.error", e);
            if (diskEntryWrite != null) {
                smb2ClientWrapper.closeOwned(diskEntryWrite, failure);
            }
            fileSystem.giveBack(smb2ClientWrapper, failure);
            throw failure;
        }
    }

    @Override
    protected void doCreateFolder() throws Exception {

        synchronized (getFileSystem()) {
            Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
            Smb2ClientWrapper client;
            try {
                client = (Smb2ClientWrapper) fileSystem.getClient();
            } catch (FileSystemException e) {
                throw new FileSystemException("vfs.provider.smb2/folder-create.error", getName(), e);
            }
            FileSystemException failure = null;
            try {
                client.createFolder(getRelPathToShare());
            } catch (Exception e) {
                failure = failure(client, "vfs.provider.smb2/folder-create.error", e);
                throw failure;
            } finally {
                fileSystem.giveBack(client, failure);
            }
        }
    }

    /**
     * Get file read handle.
     *
     * @throws FileSystemException handle create error
     */
    private Smb2InputStream getDiskEntryReadInputStream() throws FileSystemException {

        Smb2ClientWrapper smb2ClientWrapper = null;
        // The handle is owned here until the stream takes it, so a failure after the open closes it.
        DiskEntry diskEntry = null;
        Smb2FileSystem fileSystem = null;
        try {
            synchronized (getFileSystem()) {
                fileSystem = (Smb2FileSystem) getFileSystem();

                smb2ClientWrapper = (Smb2ClientWrapper) fileSystem.getClient();
                diskEntry = smb2ClientWrapper.getDiskEntryRead(getRelPathToShare());
                InputStream is = ((File) diskEntry).getInputStream();
                return new Smb2InputStream(smb2ClientWrapper, is, this, diskEntry);
            }
        } catch (Exception e) {
            if (smb2ClientWrapper == null) {
                throw new FileSystemException("vfs.provider.smb2/diskentry-create.error", getName(), e);
            }
            FileSystemException failure = failure(smb2ClientWrapper, "vfs.provider.smb2/diskentry-create.error", e);
            if (diskEntry != null) {
                smb2ClientWrapper.closeOwned(diskEntry, failure);
            }
            fileSystem.giveBack(smb2ClientWrapper, failure);
            throw failure;
        }
    }

    /**
     * Returns relative path to share.
     *
     * @return string
     */
    public String getRelPathToShare() {

        return decodeOrGet(relPathToShare);
    }

    /**
     * Decode URI value.
     *
     * @param value uri
     * @return decoded path ot path
     */
    public String decodeOrGet(String value) {

        try {
            return UriParser.decode(value);
        } catch (FileSystemException e) {
            return value;
        }
    }

    // A read that is blocked on a socket returns within one second of an abort (part 3, section 5.1, tests).
    private static final long READ_END_NANOS = TimeUnit.SECONDS.toNanos(1);

    // A slice that does not fit in its buffer.
    static boolean isBadSlice(final byte[] buffer, final int offset, final int length) {
        return offset < 0 || length < 0 || length > buffer.length - offset;
    }

    // An error that Java throws for a null buffer or a slice that does not fit, which says nothing about the client.
    static boolean isArgumentError(final RuntimeException error, final byte[] buffer, final int offset,
            final int length) {
        return buffer == null ? error instanceof NullPointerException
                : error instanceof IndexOutOfBoundsException && isBadSlice(buffer, offset, length);
    }

    // An error of a call on the client, with the cause kept and the fault that the client gives it.
    private FileSystemException failure(final Smb2ClientWrapper client, final String code, final Exception error) {
        FileSystemException failure = new FileSystemException(code, getName(), error);
        failure.setFault(client.faultOf(error));
        return failure;
    }

    @Override
    protected void doRename(final FileObject newFile) throws Exception {
        synchronized (getFileSystem()) {

            Smb2FileObject fileObject = (Smb2FileObject) newFile;
            boolean isFolder = doGetType() == FileType.FOLDER;
            Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
            Smb2ClientWrapper smb2ClientWrapper;
            try {
                smb2ClientWrapper = (Smb2ClientWrapper) fileSystem.getClient();
            } catch (FileSystemException e) {
                throw new FileSystemException("vfs.provider.smb2/diskentry-create.error", getName(), e);
            }
            // The failure that decides the give back. A failed close after a rename that returned discards the client,
            // whatever its fault.
            Throwable failure = null;
            boolean discard = false;
            try {
                // The open never creates a missing source and never shares delete.
                DiskEntry diskEntry = smb2ClientWrapper.openForRename(getRelPathToShare(), isFolder);
                RuntimeException renameError = null;
                try {
                    diskEntry.rename(fileObject.getRelPathToShare());
                } catch (RuntimeException e) {
                    renameError = e;
                }
                // The rename is classified before the close. On a dead connection the sockets are closed first, so
                // the close sends nothing and the rename, whose result is not known, is not sent again.
                RuntimeException closeError = smb2ClientWrapper.closeOwned(diskEntry, renameError);
                if (renameError != null) {
                    throw renameError;
                }
                if (closeError != null) {
                    LOG.warn("The rename of " + getName().getFriendlyURI() + " is done, but the close of its "
                            + "handle failed. The client is discarded.", closeError);
                    failure = closeError;
                    discard = true;
                }
            } catch (Exception e) {
                FileSystemException error = failure(smb2ClientWrapper, "vfs.provider.smb2/diskentry-create.error", e);
                failure = error;
                throw error;
            } finally {
                if (discard) {
                    fileSystem.discardClient(smb2ClientWrapper, failure);
                } else {
                    fileSystem.giveBack(smb2ClientWrapper, failure);
                }
            }
        }
    }

    @Override
    protected FileObject[] doListChildrenResolved() throws Exception {

        synchronized (getFileSystem()) {
            if (getType() != FileType.FOLDER) {
                throw new FileSystemException("vfs.provider/list-children-not-folder.error", this);
            }

            List<FileObject> children = new ArrayList<>();

            Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
            Smb2ClientWrapper client = (Smb2ClientWrapper) fileSystem.getClient();
            String[] childrenNames;
            FileSystemException failure = null;
            try {
                childrenNames = client.getChildren(getRelPathToShare());
            } catch (RuntimeException e) {
                failure = failure(client, "vfs.provider/list-children.error", e);
                throw failure;
            } finally {
                fileSystem.giveBack(client, failure);
            }

            for (String child : childrenNames) {
                children.add(fileSystem.getFileSystemManager()
                        .resolveFile(this, UriParser.encode(child, RESERVED_FILE_CHARS)));
            }
            return children.toArray(new FileObject[children.size()]);
        }
    }

    @Override
    protected void doDelete() throws Exception {

        synchronized (getFileSystem()) {
            Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
            Smb2ClientWrapper client = (Smb2ClientWrapper) fileSystem.getClient();
            Throwable failure = null;
            try {
                client.delete(getRelPathToShare());
            } catch (FileSystemException | RuntimeException e) {
                failure = e;
                throw e;
            } finally {
                fileSystem.giveBack(client, failure);
            }
        }
    }

    @Override
    protected long doGetLastModifiedTime() throws Exception {

        getFileInfo();
        return fileInfo.getBasicInformation().getChangeTime().toEpochMillis();
    }

    @Override
    public void close() throws FileSystemException {

        super.close();
    }

    @Override
    protected void doDetach() {

        this.fileInfo = null;
    }

    @Override
    public String toString() {

        return getName().toString();
    }

    @Override
    public boolean doSetLastModifiedTime(final long modTime) {

        // Cannot create a FileAllInformation object to override information.
        // Hence do nothing and return true
        return true;
    }

    /**
     * An InputStream that watches for the end of the file. It notes the first error of a read or a close, and apart
     * from it the first transport error of any step. After a transport error the sockets of the client are closed
     * first, so no close step waits for a reply on a dead connection, and the client is discarded.
     */
    class Smb2InputStream extends MonitorInputStream {
        private final Smb2ClientWrapper client;
        private final FileObject fileObject;
        private final DiskEntry diskEntry;
        private volatile Throwable failure;
        private volatile Throwable transportFailure;
        // The reads that are inside smbj now. A close that finds one does not give the client back for reuse.
        private final AtomicInteger activeReads = new AtomicInteger();
        private final Object readsLock = new Object();
        private volatile boolean leaseReturned;
        // Set when the close starts. The base class guards its reads with a flag of its own, and a skip has no guard
        // there, so the skip reads this one.
        private volatile boolean closing;

        public Smb2InputStream(final Smb2ClientWrapper client, final InputStream in, FileObject fileObject, DiskEntry diskEntry) {
            super(in);
            this.client = client;
            this.fileObject = fileObject;
            this.diskEntry = diskEntry;

        }

        // smbj throws unchecked. Each unchecked error that leaves this stream becomes a FileSystemException with the
        // fault that the client gives it and the cause kept, so the caller sees the same fault as the client.
        @Override
        public synchronized int read() throws IOException {
            activeReads.incrementAndGet();
            try {
                return super.read();
            } catch (IOException e) {
                note(e);
                throw e;
            } catch (RuntimeException e) {
                note(e);
                throw failure(client, "vfs.provider/read.error", e);
            } finally {
                readLeft();
            }
        }

        @Override
        public synchronized int read(final byte[] buffer, final int offset, final int length) throws IOException {
            activeReads.incrementAndGet();
            try {
                return super.read(buffer, offset, length);
            } catch (IOException e) {
                note(e);
                throw e;
            } catch (RuntimeException e) {
                if (isArgumentError(e, buffer, offset, length)) {
                    // The base class checks the arguments before it reads a byte, so smbj never saw this call.
                    throw e;
                }
                note(e);
                throw failure(client, "vfs.provider/read.error", e);
            } finally {
                readLeft();
            }
        }

        // A skip with a mark set and an empty buffer fills the buffer from smbj, so it counts and fails as a read does.
        // A skip that the buffer serves, and a request of zero or less, reach no server.
        @Override
        public synchronized long skip(final long n) throws IOException {
            activeReads.incrementAndGet();
            try {
                if (closing) {
                    // As Java answers a skip on a closed buffered stream. The client may belong to someone else now.
                    throw new IOException("Stream closed");
                }
                return super.skip(n);
            } catch (IOException e) {
                if (!closing) {
                    note(e);
                }
                throw e;
            } catch (RuntimeException e) {
                note(e);
                throw failure(client, "vfs.provider/read.error", e);
            } finally {
                readLeft();
            }
        }

        private void readLeft() {
            if (activeReads.decrementAndGet() == 0) {
                synchronized (readsLock) {
                    readsLock.notifyAll();
                }
            }
        }

        // The base class marks the stream closed before this runs, so a read that counts itself later finds the
        // stream closed and never reaches smbj. A read that counted itself before is seen here.
        private void endActiveReads() {
            if (activeReads.get() == 0) {
                return;
            }
            // The client is retired: its sockets are closed, which ends the read in smbj, and the give back finds it
            // not alive and discards it. This runs without the monitor that the read holds.
            client.abort();
            final long deadline = System.nanoTime() + READ_END_NANOS;
            synchronized (readsLock) {
                long left;
                while (activeReads.get() > 0 && (left = deadline - System.nanoTime()) > 0) {
                    try {
                        TimeUnit.NANOSECONDS.timedWait(readsLock, left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        // The first step of the close. After a transport error the sockets are closed before anything else. An
        // unchecked error of the smbj stream becomes a FileSystemException, so the base class still runs onClose.
        @Override
        protected void closeSuper() throws IOException {
            closing = true;
            endActiveReads();
            abortAfterTransportError();
            try {
                super.closeSuper();
            } catch (IOException e) {
                note(e);
                throw e;
            } catch (RuntimeException e) {
                note(e);
                throw failure(client, "vfs.provider/close-instr.error", e);
            }
        }

        /**
         * Called after the stream has been closed. Each close has its own try, so a failed close never skips the close
         * of the handle.
         */
        @Override
        protected void onClose() throws IOException {
            IOException firstException = null;
            try {
                abortAfterTransportError();
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (IOException | RuntimeException e) {
                    firstException = noted(firstException, e);
                }
                try {
                    fileObject.close();
                } catch (IOException | RuntimeException e) {
                    firstException = noted(firstException, e);
                }
                abortAfterTransportError();
                try {
                    diskEntry.close();
                } catch (RuntimeException e) {
                    firstException = noted(firstException, e);
                }
            } finally {
                leaseReturned = true;
                ((Smb2FileSystem) getAbstractFileSystem()).giveBack(client, decidingFailure());
            }
            if (firstException != null) {
                throw firstException;
            }
        }

        private void note(final Throwable error) {
            if (failure == null) {
                failure = error;
            }
            if (transportFailure == null && Smb2FileSystem.isTransport(client, error)) {
                transportFailure = error;
                // The sockets are closed at once, so a later flush or close fails at once and does not wait a second
                // request timeout. A client that was already given back belongs to someone else and is not touched.
                if (!leaseReturned) {
                    client.abort();
                }
            }
        }

        // A transport error of any step decides the give back, also after an earlier refusal. Else the first error.
        private Throwable decidingFailure() {
            return transportFailure != null ? transportFailure : failure;
        }

        private IOException noted(final IOException first, final Exception error) {
            note(error);
            if (first != null) {
                return first;
            }
            return error instanceof IOException ? (IOException) error
                    : failure(client, "vfs.provider/close-instr.error", error);
        }

        private void abortAfterTransportError() {
            final Throwable noted = decidingFailure();
            if (noted != null && Smb2FileSystem.isTransport(client, noted)) {
                client.abort();
            }
        }
    }

    /**
     * An OutputStream that notes the first error of a write, a flush or a close, and apart from it the first transport
     * error of any step. After a transport error the sockets of the client are closed first, so no close step waits
     * for a reply on a dead connection, and the client is discarded.
     */
    class Smb2OutputStream extends MonitorOutputStream {
        private final Smb2ClientWrapper client;
        private final DiskEntry diskEntry;
        private volatile Throwable failure;
        private volatile Throwable transportFailure;
        private volatile boolean leaseReturned;
        private volatile boolean closeHandled;

        public Smb2OutputStream(final Smb2ClientWrapper client, final OutputStream out, DiskEntry diskEntry) {
            super(out);
            this.client = client;
            this.diskEntry = diskEntry;
        }

        @Override
        public synchronized void write(final int b) throws IOException {
            try {
                super.write(b);
            } catch (IOException e) {
                note(e);
                throw e;
            } catch (RuntimeException e) {
                note(e);
                throw failure(client, "vfs.provider/write.error", e);
            }
        }

        @Override
        public synchronized void write(final byte[] b, final int off, final int len) throws IOException {
            assertOpen();
            // An error of the caller is thrown as Java throws it, before a byte reaches smbj, and does not mark the
            // client. The buffer of the base class would pass a long slice to smbj without a check.
            if (b == null) {
                throw new NullPointerException();
            }
            if (isBadSlice(b, off, len)) {
                throw new IndexOutOfBoundsException("offset " + off + ", length " + len + ", buffer " + b.length);
            }
            try {
                super.write(b, off, len);
            } catch (IOException e) {
                note(e);
                throw e;
            } catch (RuntimeException e) {
                note(e);
                throw failure(client, "vfs.provider/write.error", e);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            try {
                super.flush();
            } catch (IOException e) {
                note(e);
                throw e;
            } catch (RuntimeException e) {
                note(e);
                throw failure(client, "vfs.provider/write.error", e);
            }
        }

        // smbj throws unchecked. Each unchecked error that leaves this stream becomes a FileSystemException with the
        // fault that the client gives it and the cause kept. The stream of the file content wraps this one and
        // catches only an IOException, so an unchecked error would stop its close before it reaches this close.
        @Override
        public void close() throws IOException {
            abortAfterTransportError();
            try {
                super.close();
            } catch (RuntimeException e) {
                // Then the base class stops before onClose, so the close of the handle and the give back of the
                // client run here.
                note(e);
                final FileSystemException error = failure(client, "vfs.provider/close-outstr.error", e);
                if (!closeHandled) {
                    try {
                        onClose();
                    } catch (IOException closeError) {
                        if (closeError.getCause() != e) {
                            error.addSuppressed(closeError);
                        }
                    }
                }
                throw error;
            }
        }

        /**
         * Called after the stream has been closed.
         */
        @Override
        protected void onClose() throws IOException {
            closeHandled = true;
            IOException firstException = null;
            try {
                abortAfterTransportError();
                try {
                    if (out != null) {
                        out.close();
                    }
                } catch (IOException e) {
                    note(e);
                    firstException = e;
                } catch (RuntimeException e) {
                    // smbj surfaces write/flush timeouts (and other transport failures) as an
                    // unchecked SMBRuntimeException. It must not skip diskEntry.close() below,
                    // otherwise the remote SMB2 file handle is leaked until the JVM/session ends.
                    note(e);
                    firstException = failure(client, "vfs.provider/close-outstr.error", e);
                }
                abortAfterTransportError();
                try {
                    diskEntry.close();
                } catch (RuntimeException e) {
                    note(e);
                    if (firstException == null) {
                        firstException = failure(client, "vfs.provider/close-outstr.error", e);
                    }
                }
            } finally {
                leaseReturned = true;
                ((Smb2FileSystem) getAbstractFileSystem()).giveBack(client, decidingFailure());
            }
            if (firstException != null) {
                throw firstException;
            }
        }

        private void note(final Throwable error) {
            if (failure == null) {
                failure = error;
            }
            if (transportFailure == null && Smb2FileSystem.isTransport(client, error)) {
                transportFailure = error;
                // The sockets are closed at once, so a later flush or close fails at once and does not wait a second
                // request timeout. A client that was already given back belongs to someone else and is not touched.
                if (!leaseReturned) {
                    client.abort();
                }
            }
        }

        // A transport error of any step decides the give back, also after an earlier refusal. Else the first error.
        private Throwable decidingFailure() {
            return transportFailure != null ? transportFailure : failure;
        }

        private void abortAfterTransportError() {
            final Throwable noted = decidingFailure();
            if (noted != null && Smb2FileSystem.isTransport(client, noted)) {
                client.abort();
            }
        }
    }
}
