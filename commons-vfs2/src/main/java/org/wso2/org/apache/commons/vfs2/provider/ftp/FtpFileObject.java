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
package org.wso2.org.apache.commons.vfs2.provider.ftp;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;


import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileNotFolderException;
import org.wso2.org.apache.commons.vfs2.FileNotFoundException;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.RandomAccessContent;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileObject;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.UriParser;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.org.apache.commons.vfs2.util.Messages;
import org.wso2.org.apache.commons.vfs2.util.MonitorInputStream;
import org.wso2.org.apache.commons.vfs2.util.MonitorOutputStream;
import org.wso2.org.apache.commons.vfs2.util.RandomAccessMode;

/**
 * An FTP file.
 */
public class FtpFileObject extends AbstractFileObject<FtpFileSystem> {

    /**
     * An InputStream that monitors for end-of-file.
     */
    final class FtpInputStream extends MonitorInputStream {
        private final FtpClient client;
        private final NotingInputStream data;

        FtpInputStream(final FtpClient client, final InputStream in) {
            this(client, new NotingInputStream(in));
        }

        private FtpInputStream(final FtpClient client, final NotingInputStream data) {
            super(data);
            this.client = client;
            this.data = data;
        }

        FtpInputStream(final FtpClient client, final InputStream in, final int bufferSize) {
            this(client, new NotingInputStream(in), bufferSize);
        }

        private FtpInputStream(final FtpClient client, final NotingInputStream data, final int bufferSize) {
            super(data, bufferSize);
            this.client = client;
            this.data = data;
        }

        void abort() throws IOException {
            client.abort();
            close();
        }

        private boolean isTransferAbortedOkReplyCode() throws IOException {
            final List<Integer> transferAbortedOkReplyCodes = FtpFileSystemConfigBuilder
                .getInstance()
                .getTransferAbortedOkReplyCodes(getAbstractFileSystem().getFileSystemOptions());
            return transferAbortedOkReplyCodes != null && transferAbortedOkReplyCodes.contains(client.getReplyCode());
        }

        /**
         * Called after the stream has been closed. Only a good completion gives the client back.
         */
        @Override
        protected void onClose() throws IOException {
            if (data.failure() != null) {
                // The data stream failed, so the final reply is not read. The client is not in step any more.
                getAbstractFileSystem().discardClient(client);
                return;
            }
            final FileSystemException refused = run(client, c -> c.completePendingCommand()
                    || isTransferAbortedOkReplyCode() ? null
                    : refusal(c, "vfs.provider.ftp/finish-get-reply.error", true, getName()));
            if (refused != null) {
                getAbstractFileSystem().discardClient(client);
                throw refused;
            }
            getAbstractFileSystem().putClient(client);
        }
    }
    /**
     * An OutputStream that monitors for end-of-file.
     */
    private final class FtpOutputStream extends MonitorOutputStream {
        private final FtpClient client;
        private final NotingOutputStream data;

        FtpOutputStream(final FtpClient client, final OutputStream outstr) {
            this(client, new NotingOutputStream(outstr));
        }

        private FtpOutputStream(final FtpClient client, final NotingOutputStream data) {
            super(data);
            this.client = client;
            this.data = data;
        }

        /**
         * Called after this stream is closed. Only a good completion gives the client back.
         */
        @Override
        protected void onClose() throws IOException {
            if (data.failure() != null) {
                // The data stream failed, so the final reply is not read. The client is not in step any more.
                getAbstractFileSystem().discardClient(client);
                return;
            }
            final FileSystemException refused = run(client, c -> c.completePendingCommand() ? null
                    : refusal(c, "vfs.provider.ftp/finish-put-reply.error", false, getName()));
            if (refused != null) {
                getAbstractFileSystem().discardClient(client);
                throw refused;
            }
            getAbstractFileSystem().putClient(client);
        }
    }

    /**
     * Keeps the first exception of the data stream of a read.
     */
    private static final class NotingInputStream extends FilterInputStream {

        private volatile IOException failure;

        NotingInputStream(final InputStream in) {
            super(in);
        }

        IOException failure() {
            return failure;
        }

        private IOException note(final IOException e) {
            if (failure == null) {
                failure = e;
            }
            return e;
        }

        @Override
        public int read() throws IOException {
            try {
                return in.read();
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            try {
                return in.read(b, off, len);
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public long skip(final long n) throws IOException {
            try {
                return in.skip(n);
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public int available() throws IOException {
            try {
                return in.available();
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
            } catch (final IOException e) {
                throw note(e);
            }
        }
    }

    /**
     * Keeps the first exception of the data stream of a write.
     */
    private static final class NotingOutputStream extends FilterOutputStream {

        private volatile IOException failure;

        NotingOutputStream(final OutputStream out) {
            super(out);
        }

        IOException failure() {
            return failure;
        }

        private IOException note(final IOException e) {
            if (failure == null) {
                failure = e;
            }
            return e;
        }

        @Override
        public void write(final int b) throws IOException {
            try {
                out.write(b);
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            try {
                out.write(b, off, len);
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public void flush() throws IOException {
            try {
                out.flush();
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                out.close();
            } catch (final IOException e) {
                throw note(e);
            }
        }
    }

    /**
     * One call on a client that this object took.
     *
     * @param <T> the result.
     */
    private interface FtpCall<T> {
        T run(FtpClient client) throws IOException;
    }

    private static final long DEFAULT_TIMESTAMP = 0L;

    private static final FTPFile UNKNOWN = new FTPFile();

    private static final Log log = LogFactory.getLog(FtpFileObject.class);
    private volatile boolean mdtmSet;
    private final String relPath;
    // Cached info
    private volatile FTPFile ftpFile;
    // Created once and never null. Empty means that the folder was not listed yet, or that it has no entry.
    private final ConcurrentSkipListMap<String, FTPFile> childMap = new ConcurrentSkipListMap<>();

    private volatile FileObject linkDestination;

    private final AtomicBoolean inRefresh = new AtomicBoolean();

    /**
     * Constructs a new instance.
     *
     * @param fileName the file name.
     * @param fileSystem the file system.
     * @param rootName the root name.
     * @throws FileSystemException if an file system error occurs.
     */
    protected FtpFileObject(final AbstractFileName fileName, final FtpFileSystem fileSystem, final FileName rootName)
            throws FileSystemException {
        super(fileName, fileSystem);
        final String relPath = UriParser.decode(rootName.getRelativeName(fileName));
        if (".".equals(relPath)) {
            // do not use the "." as path against the ftp-server
            // e.g. the uu.net ftp-server do a recursive listing then
            // this.relPath = UriParser.decode(rootName.getPath());
            // this.relPath = ".";
            this.relPath = null;
        } else {
            this.relPath = relPath;
        }
    }

    /**
     * Attaches this file object to its file resource.
     */
    @Override
    protected void doAttach() throws IOException {
        // Get the parent folder to find the info for this file
        // VFS-210 getInfo(false);
    }

    /**
     * Creates this file as a folder.
     */
    @Override
    protected void doCreateFolder() throws Exception {
        final FtpClient client = getAbstractFileSystem().getClient();
        final FileSystemException refused = run(client, c -> c.makeDirectory(relPath) ? null
                : refusal(c, "vfs.provider.ftp/create-folder-reply.error", false, getName()));
        giveBack(client, refused);
        if (refused != null) {
            throw refused;
        }
    }

    /**
     * Deletes the file.
     */
    @Override
    protected void doDelete() throws Exception {
        synchronized (getFileSystem()) {
            final FtpClient ftpClient = getAbstractFileSystem().getClient();
            FTPFile entry = ftpFile;
            if (entry == null || entry == UNKNOWN) {
                // A listing on another thread can clear the info of this entry after the delete chose it. Then the
                // entry is looked up again here, under the same lock, before any command is sent.
                entry = lookUpForDelete(ftpClient);
            }
            final boolean folder = entry.isDirectory();
            final FileSystemException refused = run(ftpClient,
                    c -> (folder ? c.removeDirectory(relPath) : c.deleteFile(relPath)) ? null
                            : refusal(c, "vfs.provider.ftp/delete-file-reply.error", false, getName()));
            giveBack(ftpClient, refused);
            if (refused != null) {
                throw refused;
            }
            ftpFile = null;
            childMap.clear();
        }
    }

    // The entry of this file in a new listing of its folder. A link is the entry itself, so it gets DELE. A listing
    // that is refused, or that does not show the name, can not prove that the delete is done, so it is an error.
    // The client goes back or is discarded by the fault of that error.
    private FTPFile lookUpForDelete(final FtpClient client) throws IOException {
        final String name = UriParser.decode(getName().getBaseName());
        final String folderPath = UriParser.decode(getAbstractFileSystem().getRootName()
                .getRelativeName(getName().getParent()));
        final FTPFile[] entries = run(client, c -> c.listFiles(".".equals(folderPath) ? null : folderPath));
        final FileSystemException error;
        if (entries == null) {
            error = run(client, c -> refusal(c, "vfs.provider.ftp/delete-lookup-reply.error", true, getName()));
        } else {
            for (final FTPFile entry : entries) {
                if (entry != null && name.equals(entry.getName())) {
                    return entry;
                }
            }
            error = new FileSystemException("vfs.provider.ftp/delete-not-listed.error", getName());
            error.setFault(Fault.MISSING);
        }
        giveBack(client, error);
        throw error;
    }

    /**
     * Detaches this file object from its file resource.
     */
    @Override
    protected void doDetach() {
        synchronized (getFileSystem()) {
            ftpFile = null;
            childMap.clear();
            mdtmSet = false;
        }
    }

    /**
     * Fetches the children of this file, if not already cached.
     */
    private void doGetChildren() throws IOException {
        if (!childMap.isEmpty()) {
            return;
        }

        final String path = ftpFile != null && ftpFile.isSymbolicLink()
                ? getFileSystem().getFileSystemManager().resolveName(getParent().getName(), ftpFile.getLink())
                        .getPath()
                : relPath;
        final FtpClient client = getAbstractFileSystem().getClient();
        final FTPFile[] tmpChildren = run(client, c -> c.listFiles(path));
        getAbstractFileSystem().putClient(client);
        if (!ArrayUtils.isEmpty(tmpChildren)) {
            // Remove '.' and '..' elements
            for (int i = 0; i < tmpChildren.length; i++) {
                final FTPFile child = tmpChildren[i];
                if (child == null) {
                    if (log.isDebugEnabled()) {
                        log.debug(Messages.getString("vfs.provider.ftp/invalid-directory-entry.debug",
                                Integer.valueOf(i), relPath));
                    }
                    continue;
                }
                if (!".".equals(child.getName()) && !"..".equals(child.getName())) {
                    childMap.put(child.getName(), child);
                }
            }
        }
    }

    /**
     * Returns the size of the file content (in bytes).
     */
    @Override
    protected long doGetContentSize() throws Exception {
        synchronized (getFileSystem()) {
            if (ftpFile == null) {
                return 0;
            }
            if (ftpFile.isSymbolicLink()) {
                final FileObject linkDest = getLinkDestination();
                // VFS-437: Try to avoid a recursion loop.
                if (isCircular(linkDest)) {
                    return ftpFile.getSize();
                }
                return linkDest.getContent().getSize();
            }
            return ftpFile.getSize();
        }
    }

    /**
     * Creates an input stream to read the file content from.
     */
    @Override
    protected InputStream doGetInputStream(final int bufferSize) throws Exception {
        final FtpClient client = getAbstractFileSystem().getClient();
        final InputStream inputStream = run(client, c -> c.retrieveFileStream(relPath, 0));
        // VFS-210
        if (inputStream == null) {
            final FileSystemException refused = run(client,
                    c -> notRead(c, getName().toString(), "vfs.provider.ftp/read-file-reply.error"));
            giveBack(client, refused);
            throw refused;
        }
        return new FtpInputStream(client, inputStream, bufferSize);
    }

    /**
     * Gets the last modified time on an FTP file
     *
     * @see AbstractFileObject#doGetLastModifiedTime()
     */
    @Override
    protected long doGetLastModifiedTime() throws Exception {
        synchronized (getFileSystem()) {
            if (ftpFile == null) {
                return DEFAULT_TIMESTAMP;
            }
            if (ftpFile.isSymbolicLink()) {
                final FileObject linkDest = getLinkDestination();
                // VFS-437: Try to avoid a recursion loop.
                if (isCircular(linkDest)) {
                    return getTimestampMillis();
                }
                return linkDest.getContent().getLastModifiedTime();
            }
            return getTimestampMillis();
        }
    }

    /**
     * Creates an output stream to write the file content to.
     */
    @Override
    protected OutputStream doGetOutputStream(final boolean bAppend) throws Exception {
        final FtpClient client = getAbstractFileSystem().getClient();
        final OutputStream out = run(client,
                c -> bAppend ? c.appendFileStream(relPath) : c.storeFileStream(relPath));
        if (out == null) {
            final FileSystemException refused = run(client,
                    c -> refusal(c, "vfs.provider.ftp/output-error.debug", false, getName()));
            giveBack(client, refused);
            throw refused;
        }
        return new FtpOutputStream(client, out);
    }

    @Override
    protected RandomAccessContent doGetRandomAccessContent(final RandomAccessMode mode) throws Exception {
        return new FtpRandomAccessContent(this, mode);
    }

    /**
     * Determines the type of the file, returns null if the file does not exist.
     */
    @Override
    protected FileType doGetType() throws Exception {
        // VFS-210
        synchronized (getFileSystem()) {
            if (ftpFile == null) {
                setFTPFile(false);
            }

            if (ftpFile == UNKNOWN) {
                return FileType.IMAGINARY;
            }
            if (ftpFile.isDirectory()) {
                return FileType.FOLDER;
            }
            if (ftpFile.isFile()) {
                return FileType.FILE;
            }
            if (ftpFile.isSymbolicLink()) {
                final FileObject linkDest = getLinkDestination();
                // VFS-437: We need to check if the symbolic link links back to the symbolic link itself
                if (isCircular(linkDest)) {
                    // If the symbolic link links back to itself, treat it as an imaginary file to prevent following
                    // this link. If the user tries to access the link as a file or directory, the user will end up with
                    // a FileSystemException warning that the file cannot be accessed. This is to prevent the infinite
                    // call back to doGetType() to prevent the StackOverFlow
                    return FileType.IMAGINARY;
                }
                return linkDest.getType();

            }
        }
        throw new FileSystemException("vfs.provider.ftp/get-type.error", getName());
    }

    /**
     * Lists the children of the file.
     */
    @Override
    protected String[] doListChildren() throws Exception {
        // List the children of this file
        doGetChildren();

        // VFS-210. The map is never null, so an empty map is the folder with no entry.
        if (childMap.isEmpty()) {
            return new String[0];
        }

        // TODO - get rid of this children stuff
        final String[] childNames = childMap.values().stream().filter(Objects::nonNull).map(FTPFile::getName).toArray(String[]::new);

        return UriParser.encode(childNames);
    }

    @Override
    protected FileObject[] doListChildrenResolved() throws Exception {
        synchronized (getFileSystem()) {
            if (ftpFile != null && ftpFile.isSymbolicLink()) {
                final FileObject linkDest = getLinkDestination();
                // VFS-437: Try to avoid a recursion loop.
                if (isCircular(linkDest)) {
                    return null;
                }
                return linkDest.getChildren();
            }
        }
        return null;
    }

    /**
     * Renames the file
     */
    @Override
    protected void doRename(final FileObject newFile) throws Exception {
        synchronized (getFileSystem()) {
            final String newName = ((FtpFileObject) FileObjectUtils.getAbstractFileObject(newFile)).getRelPath();
            final FtpClient ftpClient = getAbstractFileSystem().getClient();
            final FileSystemException refused = run(ftpClient, c -> c.rename(relPath, newName) ? null
                    : refusal(c, "vfs.provider.ftp/rename-file-reply.error", false, getName().toString(), newFile));
            giveBack(ftpClient, refused);
            if (refused != null) {
                throw refused;
            }
            ftpFile = null;
            childMap.clear();
        }
    }

    /**
     * Called by child file objects, to locate their FTP file info.
     *
     * @param name the file name in its native form i.e. without URI stuff (%nn)
     * @param flush recreate children cache
     */
    private FTPFile getChildFile(final String name, final boolean flush) throws IOException {
        /*
         * If we should flush cached children, clear our children map unless we're in the middle of a refresh in which
         * case we've just recently refreshed our children. No need to do it again when our children are refresh()ed,
         * calling getChildFile() for themselves from within getInfo(). See getChildren().
         */
        if (flush && !inRefresh.get()) {
            childMap.clear();
        }

        // List the children of this file
        doGetChildren();

        // Look for the requested child
        return childMap.get(name);
    }

    /**
     * Returns the file's list of children.
     *
     * @return The list of children
     * @throws FileSystemException If there was a problem listing children
     * @see AbstractFileObject#getChildren()
     * @since 2.0
     */
    @Override
    public FileObject[] getChildren() throws FileSystemException {
        try {
            if (doGetType() != FileType.FOLDER) {
                throw new FileNotFolderException(getName());
            }
        } catch (final Exception ex) {
            throw new FileNotFolderException(getName(), ex);
        }

        try {
            /*
             * Wrap our parent implementation, noting that we're refreshing so that we don't refresh() ourselves and
             * each of our parents for each child. Note that refresh() will list children. Meaning, if this file
             * has C children, P parents, there will be (C * P) listings made with (C * (P + 1)) refreshes, when there
             * should really only be 1 listing and C refreshes.
             */
            inRefresh.set(true);
            return super.getChildren();
        } finally {
            inRefresh.set(false);
        }
    }

    FtpInputStream getInputStream(final long filePointer) throws IOException {
        final FtpClient client = getAbstractFileSystem().getClient();
        final InputStream instr = run(client, c -> c.retrieveFileStream(relPath, filePointer));
        // VFS-210
        if (instr == null) {
            final FileSystemException refused = run(client,
                    c -> notRead(c, getName().getFriendlyURI(), "vfs.provider.ftp/input-error.debug"));
            giveBack(client, refused);
            throw refused;
        }
        return new FtpInputStream(client, instr);
    }

    private FileObject getLinkDestination() throws FileSystemException {
        if (linkDestination == null) {
            final String path;
            synchronized (getFileSystem()) {
                path = ftpFile == null ? null : ftpFile.getLink();
            }
            final FileName parent = getName().getParent();
            final FileName relativeTo = parent == null ? getName() : parent;
            final FileName linkDestinationName = getFileSystem().getFileSystemManager().resolveName(relativeTo, path);
            linkDestination = getFileSystem().resolveFile(linkDestinationName);
        }
        return linkDestination;
    }

    String getRelPath() {
        return relPath;
    }

    /**
     * ftpFile is not null.
     */
    @SuppressWarnings("resource") // abstractFileSystem is managed in the superclass.
    private long getTimestampMillis() throws IOException {
        final FtpFileSystem abstractFileSystem = getAbstractFileSystem();
        final Boolean mdtmLastModifiedTime = FtpFileSystemConfigBuilder.getInstance()
            .getMdtmLastModifiedTime(abstractFileSystem.getFileSystemOptions());
        if (mdtmLastModifiedTime != null && mdtmLastModifiedTime.booleanValue() && !mdtmSet) {
            final FtpClient client = abstractFileSystem.getClient();
            final Instant mdtmInstant = run(client, this::mdtmTime);
            abstractFileSystem.putClient(client);
            if (mdtmInstant != null) {
                final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
                final long epochMilli = mdtmInstant.toEpochMilli();
                calendar.setTimeInMillis(epochMilli);
                ftpFile.setTimestamp(calendar);
                mdtmSet = true;
            }
        }
        return ftpFile.getTimestamp().getTime().getTime();
    }

    // The time of MDTM, or null when the server does not list MDTM or gives no reply 213. Then the time of the
    // listing is used, as when the server has no MDTM. A transport reply is an IOException of the wrapper.
    private Instant mdtmTime(final FtpClient client) throws IOException {
        if (!client.hasFeature("MDTM")) {
            return null;
        }
        final Instant time = client.mdtmInstant(relPath);
        if (time == null && log.isDebugEnabled()) {
            final int reply = client.getReplyCode();
            final String text = client.getReplyString();
            log.debug("MDTM gave no time for \"" + getName().getFriendlyURI() + "\". The server replied \""
                    + (text == null ? String.valueOf(reply) : text.trim()) + "\" (fault " + faultOf(reply, true)
                    + "). The time of the listing is used.");
        }
        return time;
    }

    /**
     * This is an over simplistic implementation for VFS-437.
     */
    private boolean isCircular(final FileObject linkDest) throws FileSystemException {
        return linkDest.getName().getPathDecoded().equals(getName().getPathDecoded());
    }

    /**
     * Called when the type or content of this file changes.
     */
    @Override
    protected void onChange() throws IOException {
        childMap.clear();

        if (getType().equals(FileType.IMAGINARY)) {
            // file is deleted, avoid server lookup
            synchronized (getFileSystem()) {
                ftpFile = UNKNOWN;
            }
            return;
        }

        setFTPFile(true);
    }

    /**
     * Called when the children of this file change.
     */
    @Override
    protected void onChildrenChanged(final FileName child, final FileType newType) {
        if (newType.equals(FileType.IMAGINARY)) {

            if (!(childMap.isEmpty())) {

                try {
                    if (childMap.containsKey(UriParser.decode(child.getBaseName()))) {
                        childMap.remove(UriParser.decode(child.getBaseName()));
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Map does not contain the " + child.getBaseName() + "in the map");
                        }
                    }
                } catch (final FileSystemException e) {
                    throw new RuntimeException(e.getMessage());
                }

            } else {
                if (log.isDebugEnabled()) {
                    log.debug("EMPTY_FTP_FILE_MAP returned empty collection.");
                }
            }
        } else {
            // if child was added we have to rescan the children
            // TODO - get rid of this
            childMap.clear();
        }
    }

    // Runs one call on a client that this object took. An IOException of the call leaves the client out of step, so
    // the client is discarded. Any other error gives it back, as before.
    private <T> T run(final FtpClient client, final FtpCall<T> call) throws IOException {
        try {
            return call.run(client);
        } catch (final IOException e) {
            getAbstractFileSystem().discardClient(client);
            throw e;
        } catch (final RuntimeException | Error e) {
            getAbstractFileSystem().putClient(client);
            throw e;
        }
    }

    // Gives the client back after a refusal, or discards it when the reply is a transport reply. The fault was read
    // while the caller still held the client. A refusal that is not a transport reply leaves the client in step.
    private void giveBack(final FtpClient client, final FileSystemException refused) {
        if (refused != null && refused.getFault() == Fault.TRANSPORT) {
            getAbstractFileSystem().discardClient(client);
        } else {
            getAbstractFileSystem().putClient(client);
        }
    }

    // The error of a command that the server refused. The reply is read while the caller still holds the client, and
    // it is the last value of the message.
    private static FileSystemException refusal(final FtpClient client, final String code, final boolean read,
            final Object... info) throws IOException {
        final int reply = client.getReplyCode();
        final String text = client.getReplyString();
        final Object[] values = Arrays.copyOf(info, info.length + 1);
        values[info.length] = text == null ? String.valueOf(reply) : text.trim();
        final FileSystemException error = new FileSystemException(code, values);
        error.setFault(faultOf(reply, read));
        return error;
    }

    // A read that got no stream. Only a reply 550 is the file not found, and that is a hint, not a proof.
    private static FileSystemException notRead(final FtpClient client, final String name, final String code)
            throws IOException {
        if (client.getReplyCode() == FTPReply.FILE_UNAVAILABLE) {
            final FileSystemException error = new FileNotFoundException(name);
            error.setFault(Fault.MISSING);
            return error;
        }
        return refusal(client, code, true, name);
    }

    /**
     * Gives the fault of a reply by the FTP table of the fault classes. A reply 550 to a read, a size or a time
     * request is the hint that the name is not there. A reply 550 to a call that changes the server is a refusal.
     *
     * @param reply the reply code.
     * @param read true for a call that only reads.
     * @return the fault.
     */
    static Fault faultOf(final int reply, final boolean read) {
        switch (reply) {
        case FTPReply.SERVICE_NOT_AVAILABLE:
        case FTPReply.CANNOT_OPEN_DATA_CONNECTION:
        case FTPReply.TRANSFER_ABORTED:
            return Fault.TRANSPORT;
        case FTPReply.FILE_ACTION_NOT_TAKEN:
            return Fault.BUSY;
        case FTPReply.FILE_UNAVAILABLE:
            return read ? Fault.MISSING : Fault.APPLICATION;
        default:
            return Fault.APPLICATION;
        }
    }

    /**
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public void refresh() throws FileSystemException {
        if (inRefresh.compareAndSet(false, true)) {
            try {
                super.refresh();
                synchronized (getFileSystem()) {
                    ftpFile = null;
                }
                /*
                 * VFS-210 try { // this will tell the parent to recreate its children collection getInfo(true); } catch
                 * (IOException e) { throw new FileSystemException(e); }
                 */
            } finally {
                inRefresh.set(false);
            }
        }
    }

    /**
     * Sets the internal FTPFile for this instance.
     */
    private void setFTPFile(final boolean flush) throws IOException {
        synchronized (getFileSystem()) {
            final FtpFileObject parent = (FtpFileObject) FileObjectUtils.getAbstractFileObject(getParent());
            final FTPFile newFileInfo;
            if (parent != null) {
                newFileInfo = parent.getChildFile(UriParser.decode(getName().getBaseName()), flush);
            } else {
                // Assume the root is a directory and exists
                newFileInfo = new FTPFile();
                newFileInfo.setType(FTPFile.DIRECTORY_TYPE);
            }
            ftpFile = newFileInfo == null ? UNKNOWN : newFileInfo;
        }
    }
}
