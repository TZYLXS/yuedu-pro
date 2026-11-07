package me.ag2s.base;

import static me.ag2s.base.ThrowableUtils.rethrowAsIOException;

import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.annotation.RequiresApi;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 读取ParcelFileDescriptor的工具类
 *
 * API 19 移植说明：android.system.Os 只在 21+ 才有，
 * 4.4 上走 FileInputStream 的 channel（同一个 fd，位置共享），功能等价。
 */
@SuppressWarnings("unused")
public final class PfdHelper {

    /**
     * 读取基本类型的buffer
     */
    private static final byte[] readBuffer = new byte[8];

    // 4.4 回退路径：同一个 ParcelFileDescriptor 复用同一个流，保证文件位置连续
    private static final Map<ParcelFileDescriptor, FileInputStream> legacyStreams =
            new WeakHashMap<>();

    private static synchronized FileInputStream legacyStream(ParcelFileDescriptor pfd)
            throws IOException {
        FileInputStream stream = legacyStreams.get(pfd);
        if (stream == null) {
            stream = new FileInputStream(pfd.getFileDescriptor());
            legacyStreams.put(pfd, stream);
        }
        return stream;
    }

    public static void seek(ParcelFileDescriptor pfd, long pos) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            seekApi21(pfd, pos);
        } else {
            legacyStream(pfd).getChannel().position(pos);
        }
    }

    public static long getFilePointer(ParcelFileDescriptor pfd) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return filePointerApi21(pfd);
        }
        return legacyStream(pfd).getChannel().position();
    }

    public static long length(ParcelFileDescriptor pfd) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return lengthApi21(pfd);
        }
        return legacyStream(pfd).getChannel().size();
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private static void seekApi21(ParcelFileDescriptor pfd, long pos) throws IOException {
        try {
            android.system.Os.lseek(pfd.getFileDescriptor(), pos, android.system.OsConstants.SEEK_SET);
        } catch (android.system.ErrnoException e) {
            throw rethrowAsIOException(e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private static long filePointerApi21(ParcelFileDescriptor pfd) throws IOException {
        try {
            return android.system.Os.lseek(pfd.getFileDescriptor(), 0, android.system.OsConstants.SEEK_CUR);
        } catch (android.system.ErrnoException e) {
            throw rethrowAsIOException(e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private static long lengthApi21(ParcelFileDescriptor pfd) throws IOException {
        try {
            return android.system.Os.fstat(pfd.getFileDescriptor()).st_size;
        } catch (android.system.ErrnoException e) {
            throw rethrowAsIOException(e);
        }
    }

    private static int readBytes(ParcelFileDescriptor pfd, byte[] b, int off, int len)
            throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return readBytesApi21(pfd, b, off, len);
        }
        return legacyStream(pfd).read(b, off, len);
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private static int readBytesApi21(ParcelFileDescriptor pfd, byte[] b, int off, int len)
            throws IOException {
        try {
            return android.system.Os.read(pfd.getFileDescriptor(), b, off, len);
        } catch (android.system.ErrnoException e) {
            throw rethrowAsIOException(e);
        }
    }

    public static int read(ParcelFileDescriptor pfd) throws IOException {
        return (read(pfd, readBuffer, 0, 1) != -1) ? readBuffer[0] & 0xff : -1;
    }

    public static int read(ParcelFileDescriptor pfd, byte[] b, int off, int len) throws IOException {
        return readBytes(pfd, b, off, len);
    }

    public static int read(ParcelFileDescriptor pfd, byte[] b) throws IOException {
        return readBytes(pfd, b, 0, b.length);
    }

    public static void readFully(ParcelFileDescriptor pfd, byte[] b) throws IOException {
        readFully(pfd, b, 0, b.length);
    }

    public static void readFully(ParcelFileDescriptor pfd, byte[] b, int off, int len) throws IOException {
        int n = 0;
        do {
            int count = read(pfd, b, off + n, len - n);
            if (count < 0)
                throw new EOFException();
            n += count;
        } while (n < len);
    }


    public static int skipBytes(ParcelFileDescriptor pfd, int n) throws IOException {
        long pos;
        long len;
        long newpos;

        if (n <= 0) {
            return 0;
        }
        pos = getFilePointer(pfd);
        len = length(pfd);
        newpos = pos + n;
        if (newpos > len) {
            newpos = len;
        }
        seek(pfd, newpos);

        /* return the actual number of bytes skipped */
        return (int) (newpos - pos);
    }
}
