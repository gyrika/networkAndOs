package lanpeer;

import java.io.IOException;

import lanpeer.cli.Options;
import lanpeer.cli.TerminalApp;

/** Entry point: {@code java -jar lanpeer.jar --name Alice}. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println(Options.USAGE);
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.println(Options.USAGE);
            return;
        }
        try {
            new TerminalApp(options, System.in, System.out).run();
        } catch (IOException e) {
            System.out.println("Could not start on TCP port " + options.port() + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
