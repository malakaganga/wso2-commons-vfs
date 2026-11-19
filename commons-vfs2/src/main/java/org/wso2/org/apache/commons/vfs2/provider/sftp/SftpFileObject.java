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
package org.wso2.org.apache.commons.vfs2.provider.sftp;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileNotFoundException;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.NameScope;
import org.wso2.org.apache.commons.vfs2.RandomAccessContent;
import org.wso2.org.apache.commons.vfs2.VFS;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileObject;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.UriParser;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.org.apache.commons.vfs2.util.MonitorInputStream;
import org.wso2.org.apache.commons.vfs2.util.MonitorOutputStream;
import org.wso2.org.apache.commons.vfs2.util.PosixPermissions;
import org.wso2.org.apache.commons.vfs2.util.RandomAccessMode;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

/**
 * An SFTP file.
 */
public class SftpFileObject extends AbstractFileObject<SftpFileSystem> {
    private static final Log LOG = LogFactory.getLog(SftpFileObject.class);

    /**
     * An InputStream that monitors for end-of-file.
     * <p>
     * It notes the first exception of a read or a close on the stream of JSch. Its close puts the channel back only
     * when none was noted. Else it discards the channel and its session.
     * </p>
     */
    private final class SftpInputStream extends MonitorInputStream {
        private final ChannelSftp channel;

        private final NotedInput source;

        SftpInputStream(final ChannelSftp channel, final InputStream in) {
            this(channel, new NotedInput(in));
        }

        SftpInputStream(final ChannelSftp channel, final InputStream in, final int bufferSize) {
            this(channel, new NotedInput(in), bufferSize);
        }

        private SftpInputStream(final ChannelSftp channel, final NotedInput source) {
            super(source);
            this.channel = channel;
            this.source = source;
        }

        private SftpInputStream(final ChannelSftp channel, final NotedInput source, final int bufferSize) {
            super(source, bufferSize);
            this.channel = channel;
            this.source = source;
        }

        /**
         * Called after the stream has been closed.
         */
        @Override
        protected void onClose() throws IOException {
            releaseNoted(channel, source.failure);
        }
    }

    /**
     * An OutputStream that wraps an sftp OutputStream, and closes the channel when the stream is closed.
     * <p>
     * It notes the first exception of a write, a flush or a close on the stream of JSch. Its close puts the channel
     * back only when none was noted. Else it discards the channel and its session.
     * </p>
     */
    private final class SftpOutputStream extends MonitorOutputStream {
        private final ChannelSftp channel;

        private final NotedOutput target;

        SftpOutputStream(final ChannelSftp channel, final OutputStream out) {
            this(channel, new NotedOutput(out));
        }

        private SftpOutputStream(final ChannelSftp channel, final NotedOutput target) {
            super(target);
            this.channel = channel;
            this.target = target;
        }

        /**
         * Called after this stream is closed.
         */
        @Override
        protected void onClose() throws IOException {
            releaseNoted(channel, target.failure);
        }
    }

    /**
     * The stream of JSch for a read, which notes its first exception.
     */
    private static final class NotedInput extends FilterInputStream {

        private volatile Throwable failure;

        NotedInput(final InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            }
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            try {
                return super.read(buffer, offset, length);
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            }
        }

        @Override
        public long skip(final long count) throws IOException {
            try {
                return super.skip(count);
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            }
        }

        private void note(final Throwable error) {
            if (failure == null) {
                failure = error;
            }
        }
    }

    /**
     * The stream of JSch for a write, which notes its first exception.
     */
    private static final class NotedOutput extends FilterOutputStream {

        private volatile Throwable failure;

        NotedOutput(final OutputStream out) {
            super(out);
        }

        @Override
        public void write(final int value) throws IOException {
            try {
                out.write(value);
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            }
        }

        @Override
        public void write(final byte[] buffer, final int offset, final int length) throws IOException {
            try {
                out.write(buffer, offset, length);
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            }
        }

        @Override
        public void flush() throws IOException {
            try {
                out.flush();
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            }
        }

        // The stream above flushes before it closes, so the close does not flush again.
        @Override
        public void close() throws IOException {
            try {
                out.close();
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            }
        }

        private void note(final Throwable error) {
            if (failure == null) {
                failure = error;
            }
        }
    }
    private static final long MOD_TIME_FACTOR = 1000L;

    private SftpATTRS attrs;

    private final String relPath;

    /**
     * Constructs a new instance.
     *
     * @param fileName the file name.
     * @param fileSystem the file system.
     * @throws FileSystemException if a file system error occurs.
     */
    protected SftpFileObject(final AbstractFileName fileName, final SftpFileSystem fileSystem) throws FileSystemException {
        super(fileName, fileSystem);
        relPath = UriParser.decode(fileSystem.getRootName().getRelativeName(fileName));
    }

    /**
     * Creates this file as a folder.
     */
    @Override
    protected void doCreateFolder() throws Exception {
        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        Throwable failure = null;
        try {
            channel.mkdir(relPath);
        } catch (final Throwable e) {
            failure = e;
            throw e;
        } finally {
            release(channel, failure);
        }
    }

    /**
     * Deletes the file.
     */
    @Override
    protected void doDelete() throws Exception {
        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        // Only the failure of a call on this channel decides what happens to it.
        Throwable failure = null;
        try {
            final boolean file = isFile();
            try {
                if (file) {
                    channel.rm(relPath);
                } else {
                    channel.rmdir(relPath);
                }
            } catch (final Throwable e) {
                failure = e;
                throw e;
            }
        } finally {
            release(channel, failure);
        }
    }

    /** @since 2.0 */
    @Override
    protected synchronized void doDetach() throws Exception {
        attrs = null;
    }

    /**
     * Returns the size of the file content (in bytes).
     */
    @Override
    protected synchronized long doGetContentSize() throws Exception {
        if (attrs == null || (attrs.getFlags() & SftpATTRS.SSH_FILEXFER_ATTR_SIZE) == 0) {
            throw new FileSystemException("vfs.provider.sftp/unknown-size.error");
        }
        return attrs.getSize();
    }

    /**
     * Creates an input stream to read the file content from.
     */
    @SuppressWarnings("resource")
    @Override
    protected InputStream doGetInputStream(final int bufferSize) throws Exception {
        // VFS-113: avoid NPE.
        synchronized (getAbstractFileSystem()) {
            final ChannelSftp channel = getAbstractFileSystem().getChannel();
            // return channel.get(getName().getPath());
            // hmmm - using the in memory method is soooo much faster ...

            // TODO - Don't read the entire file into memory. Use the
            // stream-based methods on ChannelSftp once they work properly

            /*
             * final ByteArrayOutputStream outstr = new ByteArrayOutputStream(); channel.get(relPath, outstr); outstr.close();
             * return new ByteArrayInputStream(outstr.toByteArray());
             */

            final InputStream inputStream;
            // VFS-210: sftp allows to gather an input stream even from a directory and will
            // fail on first read. So we need to check the type anyway. No call on this channel ran yet, so it goes back.
            final boolean hasContent;
            try {
                hasContent = getType().hasContent();
            } catch (final Throwable e) {
                release(channel, null);
                throw e;
            }
            if (!hasContent) {
                release(channel, null);
                throw new FileSystemException("vfs.provider/read-not-file.error", getName());
            }
            try {
                inputStream = channel.get(relPath);
            } catch (final SftpException e) {
                release(channel, e);
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE && e.getCause() == null) {
                    throw new FileNotFoundException(getName());
                }
                throw new FileSystemException(e);
            } catch (final Throwable e) {
                release(channel, e);
                throw e;
            }
            return new SftpInputStream(channel, inputStream, bufferSize);
        }
    }

    @Override
    protected synchronized long doGetLastModifiedTime() throws Exception {
        if (attrs == null || (attrs.getFlags() & SftpATTRS.SSH_FILEXFER_ATTR_ACMODTIME) == 0) {
            throw new FileSystemException("vfs.provider.sftp/unknown-modtime.error");
        }
        return attrs.getMTime() * MOD_TIME_FACTOR;
    }

    /**
     * Creates an output stream to write the file content to.
     */
    @Override
    protected OutputStream doGetOutputStream(final boolean bAppend) throws Exception {
        // TODO - Don't write the entire file into memory. Use the stream-based
        // methods on ChannelSftp once the work properly
        /*
         * final ChannelSftp channel = getAbstractFileSystem().getChannel(); return new SftpOutputStream(channel);
         */

        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        try {
            return new SftpOutputStream(channel, channel.put(relPath, bAppend ? ChannelSftp.APPEND : ChannelSftp.OVERWRITE));
        } catch (final Throwable ex) {
            release(channel, ex);
            throw ex;
        }

    }

    @Override
    protected RandomAccessContent doGetRandomAccessContent(final RandomAccessMode mode) throws Exception {
        return new SftpRandomAccessContent(this, mode);
    }

    /**
     * Determines the type of this file, returns null if the file does not exist.
     */
    @Override
    protected synchronized FileType doGetType() throws Exception {
        if (attrs == null) {
            statSelf();
        }

        if (attrs == null) {
            return FileType.IMAGINARY;
        }

        if ((attrs.getFlags() & SftpATTRS.SSH_FILEXFER_ATTR_PERMISSIONS) == 0) {
            throw new FileSystemException("vfs.provider.sftp/unknown-permissions.error");
        }
        if (attrs.isDir()) {
            return FileType.FOLDER;
        }
        return FileType.FILE;
    }

    @Override
    protected boolean doIsExecutable() throws Exception {
        if (isPermissionCheckRequired()) {
            return getPermissions(true).isExecutable();
        }
        return true;
    }

    @Override
    protected boolean doIsReadable() throws Exception {
        if (isPermissionCheckRequired()) {
            return getPermissions(true).isReadable();
        }
        return true;
    }

    @Override
    protected boolean doIsWriteable() throws Exception {
        if (isPermissionCheckRequired()) {
            return getPermissions(true).isWritable();
        }
        return true;
    }

    private boolean isPermissionCheckRequired() {
        final String permissionCheck = SftpFileSystemConfigBuilder.getInstance()
                .getAvoidPermissionCheck(getAbstractFileSystem().getFileSystemOptions());
        return permissionCheck == null || !permissionCheck.equalsIgnoreCase("true");
    }

    /**
     * Lists the children of this file.
     */
    @Override
    protected String[] doListChildren() throws Exception {
        // use doListChildrenResolved for performance
        return null;
    }

    /**
     * Lists the children of this file.
     */
    @Override
    protected FileObject[] doListChildrenResolved() throws Exception {
        // should not require a round-trip because type is already set.
        if (isFile()) {
            return null;
        }
        // List the contents of the folder
        final Vector<?> vector;
        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        // The failure that decides what happens to the channel.
        final Throwable[] failure = new Throwable[1];
        try {
            vector = list(channel, failure);
        } catch (final Throwable e) {
            if (failure[0] == null) {
                failure[0] = e;
            }
            throw e;
        } finally {
            release(channel, failure[0]);
        }
        if (vector == null) {
            // VFS-210: seems not to be a directory
            return null;
        }
        FileSystemException.requireNonNull(vector, "vfs.provider.sftp/list-children.error");

        // Extract the child names
        final ArrayList<FileObject> children = new ArrayList<>();
        for (@SuppressWarnings("unchecked") // OK because ChannelSftp.ls() is documented to return Vector<LsEntry>
             final Iterator<LsEntry> iterator = (Iterator<LsEntry>) vector.iterator(); iterator.hasNext();) {
            final LsEntry stat = iterator.next();

            String name = stat.getFilename();
            if (VFS.isUriStyle() && stat.getAttrs().isDir() && name.charAt(name.length() - 1) != '/') {
                name += "/";
            }

            if (name.equals(".") || name.equals("..") || name.equals("./") || name.equals("../")) {
                continue;
            }

            final FileObject fo = getFileSystem().resolveFile(getFileSystem().getFileSystemManager()
                    .resolveName(getName(), UriParser.encode(name), NameScope.CHILD));

            ((SftpFileObject) FileObjectUtils.getAbstractFileObject(fo)).setStat(stat.getAttrs());

            children.add(fo);
        }

        return children.toArray(EMPTY_ARRAY);
    }

    /**
     * Lists this folder on the channel. A failure with a cause, which is a broken connection, stops at once and the
     * caller discards the channel with its session. After a reply of the server the old way runs: a change into the
     * folder and a list of ".", and then the change back. A channel that can not change back is in the wrong folder,
     * so it is disconnected and never pooled.
     *
     * @param channel the channel.
     * @param failure set to the failure that decides what happens to the channel.
     * @return the listing, or null when the server says that this is not a folder.
     */
    private Vector<?> list(final ChannelSftp channel, final Throwable[] failure) throws Exception {
        try {
            // try the direct way to list the directory on the server to avoid too many round trips
            return channel.ls(relPath);
        } catch (final SftpException e) {
            if (!SftpFileSystem.isReplyOrSuccess(e)) {
                failure[0] = e;
                throw e;
            }
        }
        String workingDirectory = null;
        try {
            if (relPath != null) {
                workingDirectory = channel.pwd();
                channel.cd(relPath);
            }
        } catch (final SftpException ex) {
            failure[0] = ex;
            if (!SftpFileSystem.isReplyOrSuccess(ex)) {
                throw ex;
            }
            return null;
        }
        Vector<?> vector = null;
        SftpException lsEx = null;
        try {
            vector = channel.ls(".");
        } catch (final SftpException ex) {
            failure[0] = ex;
            if (!SftpFileSystem.isReplyOrSuccess(ex)) {
                throw ex;
            }
            lsEx = ex;
        }
        if (relPath != null) {
            try {
                channel.cd(workingDirectory);
            } catch (final SftpException xe) {
                failure[0] = xe;
                channel.disconnect();
                final FileSystemException restore = new FileSystemException(
                        "vfs.provider.sftp/change-work-directory-back.error", workingDirectory, xe);
                if (lsEx != null) {
                    restore.addSuppressed(lsEx);
                }
                throw restore;
            }
        }
        if (lsEx != null) {
            throw lsEx;
        }
        return vector;
    }

    /**
     * Renames the file.
     */
    @Override
    protected void doRename(final FileObject newFile) throws Exception {
        final SftpFileObject newSftpFileObject = (SftpFileObject) FileObjectUtils.getAbstractFileObject(newFile);
        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        Throwable failure = null;
        try {
            channel.rename(relPath, newSftpFileObject.relPath);
        } catch (final Throwable e) {
            failure = e;
            throw e;
        } finally {
            release(channel, failure);
        }
    }

    @Override
    protected synchronized boolean doSetExecutable(final boolean executable, final boolean ownerOnly) throws Exception {
        final PosixPermissions permissions = getPermissions(false);
        final int newPermissions = permissions.makeExecutable(executable, ownerOnly);
        if (newPermissions == permissions.getPermissions()) {
            return true;
        }

        attrs.setPERMISSIONS(newPermissions);
        flushStat();

        return true;
    }

    /**
     * Sets the last modified time of this file. Is only called if {@link #doGetType} does not return
     * {@link FileType#IMAGINARY}.
     *
     * @param modtime is modification time in milliseconds. SFTP protocol can send times with nanosecond precision but
     *            at the moment jsch send them with second precision.
     */
    @Override
    protected synchronized boolean doSetLastModifiedTime(final long modtime) throws Exception {
        final int newMTime = (int) (modtime / MOD_TIME_FACTOR);
        attrs.setACMODTIME(attrs.getATime(), newMTime);
        flushStat();
        return true;
    }

    @Override
    protected boolean doSetReadable(final boolean readable, final boolean ownerOnly) throws Exception {
        final PosixPermissions permissions = getPermissions(false);
        final int newPermissions = permissions.makeReadable(readable, ownerOnly);
        if (newPermissions == permissions.getPermissions()) {
            return true;
        }

        attrs.setPERMISSIONS(newPermissions);
        flushStat();

        return true;
    }

    @Override
    protected synchronized boolean doSetWritable(final boolean writable, final boolean ownerOnly) throws Exception {
        final PosixPermissions permissions = getPermissions(false);
        final int newPermissions = permissions.makeWritable(writable, ownerOnly);
        if (newPermissions == permissions.getPermissions()) {
            return true;
        }

        attrs.setPERMISSIONS(newPermissions);
        flushStat();

        return true;
    }

    private synchronized void flushStat() throws IOException, SftpException {
        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        Throwable failure = null;
        try {
            channel.setStat(relPath, attrs);
        } catch (final Throwable e) {
            failure = e;
            throw e;
        } finally {
            release(channel, failure);
        }
    }

    /**
     * Creates an input stream to read the file content from. The input stream is starting at the given position in the
     * file.
     */
    InputStream getInputStream(final long filePointer) throws IOException {
        final String path = getName().getPathDecoded();
        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        // Using InputStream directly from the channel
        // is much faster than the memory method.
        try {
            return new SftpInputStream(channel, channel.get(path, null, filePointer));
        } catch (final SftpException e) {
            release(channel, e);
            throw new FileSystemException(e);
        } catch (final Throwable e) {
            release(channel, e);
            throw e;
        }
    }

    /**
     * Returns the POSIX type permissions of the file.
     *
     * @param checkIds {@code true} if user and group ID should be checked (needed for some access rights checks)
     * @return A PosixPermission object
     * @throws Exception If an error occurs
     * @since 2.1
     */
    protected synchronized PosixPermissions getPermissions(final boolean checkIds) throws Exception {
        statSelf();
        boolean isInGroup = false;
        if (checkIds) {
            if (getAbstractFileSystem().isExecDisabledForCheck()) {
                // Exec is disabled, so we won't be able to ascertain the current user's UID and GID.
                // Return "always-true" permissions as a workaround, knowing that the SFTP server won't
                // let us perform unauthorized actions anyway.
                return new UserIsOwnerPosixPermissions(attrs.getPermissions());
            }

            final int[] groupsIds = getAbstractFileSystem().getGroupsIdsForCheck();
            // The probe of the groups can pass its deadline too, and then exec counts as disabled.
            if (getAbstractFileSystem().isExecDisabledForCheck()) {
                return new UserIsOwnerPosixPermissions(attrs.getPermissions());
            }
            for (final int groupId : groupsIds) {
                if (groupId == attrs.getGId()) {
                    isInGroup = true;
                    break;
                }
            }
        }
        final boolean isOwner = checkIds && attrs.getUId() == getAbstractFileSystem().getUIdForCheck();
        return new PosixPermissions(attrs.getPermissions(), isOwner, isInGroup);
    }

    /**
     * Called when the type or content of this file changes.
     */
    @Override
    protected void onChange() throws Exception {
        statSelf();
    }

    // Every call site gives its channel back here, with the exception that ended its call or null.
    @SuppressWarnings("resource") // does not allocate
    private void release(final ChannelSftp channel, final Throwable failure) {
        getAbstractFileSystem().releaseChannel(channel, failure);
    }

    // A stream that noted an exception never puts its channel back.
    @SuppressWarnings("resource") // does not allocate
    private void releaseNoted(final ChannelSftp channel, final Throwable failure) {
        if (failure == null) {
            release(channel, null);
        } else {
            getAbstractFileSystem().discardChannel(channel);
        }
    }

    /**
     * Sets attrs from listChildrenResolved
     */
    private synchronized void setStat(final SftpATTRS attrs) {
        this.attrs = attrs;
    }

    /**
     * Fetches file attributes from server.
     *
     * @throws IOException if an error occurs.
     */
    private synchronized void statSelf() throws Exception {
        // The fast window of this call opens here.
        final long start = System.nanoTime();
        final SftpFileSystem fileSystem = getAbstractFileSystem();
        ChannelSftp channel = fileSystem.getChannel();
        try {
            setStat(channel.stat(relPath));
        } catch (final SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE && e.getCause() == null) {
                release(channel, e);
                attrs = null;
                LOG.debug("SFTP server returned no such file for stat of '" + relPath + "', so the file is missing");
                return;
            }
            // The channel that failed is not handed back. A reply of the server leaves the session in use, and any
            // other failure discards it.
            if (SftpFileSystem.isReplyOrSuccess(e)) {
                channel.disconnect();
            }
            release(channel, e);
            if (!Faults.isFast(start)) {
                // A slow failure gets no retry.
                throw e;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Stat of '" + relPath + "' failed, retrying it on a fresh SFTP channel", e);
            }
            channel = fileSystem.getChannel();
            try {
                setStat(channel.stat(relPath));
            } catch (final SftpException retryEx) {
                final boolean connected = channel.isConnected();
                release(channel, retryEx);
                // A failure with a cause is a broken connection, whatever its id. Some servers reply with a failure
                // for a file that is not there, so on a channel that is still up that reply counts as missing too.
                if (retryEx.getCause() == null && (retryEx.id == ChannelSftp.SSH_FX_NO_SUCH_FILE
                        || retryEx.id == ChannelSftp.SSH_FX_FAILURE && connected)) {
                    attrs = null;
                    LOG.debug("Stat retry of '" + relPath + "' found no file, so the file is missing");
                    return;
                }
                throw retryEx;
            } catch (final Throwable retryError) {
                release(channel, retryError);
                throw retryError;
            }
            release(channel, null);
            return;
        } catch (final Throwable error) {
            release(channel, error);
            throw error;
        }
        release(channel, null);
    }

}
