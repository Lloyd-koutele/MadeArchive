package made.archive.util;

import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class InMemoryMultipartFile implements MultipartFile
{
    private final String filename;
    private final byte[] content;
    private final String contentType;

    public InMemoryMultipartFile(String filename, byte[] content, String contentType)
    {
        this.filename = filename;
        this.content = content;
        this.contentType = contentType;
    }

    @Override
    public String getName()
    {
        return filename;
    }

    @Override
    public String getOriginalFilename()
    {
        return filename;
    }

    @Override
    public String getContentType()
    {
        return contentType;
    }

    @Override
    public boolean isEmpty()
    {
        return content == null || content.length == 0;
    }

    @Override
    public long getSize()
    {
        return content.length;
    }

    @Override
    public byte[] getBytes() throws IOException
    {
        return content;
    }

    @Override
    public InputStream getInputStream() throws IOException
    {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(File dest) throws IOException, IllegalStateException
    {
        throw new UnsupportedOperationException(
            "transferTo non supporté pour InMemoryMultipartFile");
    }
}