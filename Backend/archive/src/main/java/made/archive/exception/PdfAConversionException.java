package made.archive.exception;

public class PdfAConversionException extends Exception
{
    public PdfAConversionException(String message)
    {
        super(message);
    }

    public PdfAConversionException(String message, Throwable cause)
    {
        super(message, cause);
    }
}