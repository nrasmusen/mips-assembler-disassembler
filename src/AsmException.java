// An input error, with the line number it refers to (0 = no specific line)
public class AsmException extends Exception {
    private static final long serialVersionUID = 1L;

    public final int line;

    public AsmException(int line, String message) {
        super(message);
        this.line = line;
    }
}
