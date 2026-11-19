package lanpeer.cli;

import lanpeer.net.PeerNode;

/** Command-line options, mirroring the Python version's flags. */
public record Options(String name, int port, int discoveryPort, boolean discovery, boolean help) {

    public static final String USAGE = """
            usage: lanpeer [--name NAME] [--port PORT] [--discovery-port PORT] [--no-discovery]

            Terminal peer-to-peer chat and file sharing on a LAN

            options:
              --name NAME            Your displayed name (default: user or host name)
              --port PORT            TCP listening port (default: 5001)
              --discovery-port PORT  UDP discovery port (default: 5000)
              --no-discovery         Use /connect instead of automatic discovery
              -h, --help             Show this help and exit""";

    /** Parses {@code args}; throws {@link IllegalArgumentException} with a user-facing message on bad input. */
    public static Options parse(String... args) {
        String name = null;
        int port = PeerNode.DEFAULT_PORT;
        int discoveryPort = PeerNode.DEFAULT_DISCOVERY_PORT;
        boolean discovery = true;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String flag = arg;
            String inlineValue = null;
            int equals = arg.indexOf('=');
            if (arg.startsWith("--") && equals > 0) {
                flag = arg.substring(0, equals);
                inlineValue = arg.substring(equals + 1);
            }
            switch (flag) {
                case "-h", "--help" -> help = true;
                case "--no-discovery" -> discovery = false;
                case "--name" -> {
                    name = inlineValue != null ? inlineValue : nextValue(args, ++i, flag);
                }
                case "--port" -> port = parsePort(inlineValue != null ? inlineValue : nextValue(args, ++i, flag), flag, true);
                case "--discovery-port" -> discoveryPort = parsePort(
                        inlineValue != null ? inlineValue : nextValue(args, ++i, flag), flag, false);
                default -> throw new IllegalArgumentException("unrecognized argument: " + arg);
            }
        }
        return new Options(name == null ? PeerNode.defaultName() : name, port, discoveryPort, discovery, help);
    }

    private static String nextValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("argument " + flag + ": expected a value");
        }
        return args[index];
    }

    private static int parsePort(String text, String flag, boolean allowZero) {
        try {
            int value = Integer.parseInt(text.strip());
            if (value >= (allowZero ? 0 : 1) && value <= 65_535) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the error below
        }
        throw new IllegalArgumentException("argument " + flag + ": invalid port '" + text + "'");
    }
}
