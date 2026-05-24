import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class RconSend {
    private static final int AUTH = 3;
    private static final int COMMAND = 2;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            throw new IllegalArgumentException("Usage: java RconSend.java <host> <port> <password> <command...>");
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String password = args[2];
        String command = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));

        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(10000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());

            send(output, 1, AUTH, password);
            Packet auth = read(input);
            if (auth.id() == -1) {
                throw new IllegalStateException("RCON authentication failed.");
            }

            send(output, 2, COMMAND, command);
            Packet response = read(input);
            System.out.println(response.body());
        }
    }

    private static void send(DataOutputStream output, int id, int type, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        int length = 4 + 4 + bytes.length + 2;
        ByteBuffer buffer = ByteBuffer.allocate(4 + length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(length);
        buffer.putInt(id);
        buffer.putInt(type);
        buffer.put(bytes);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        output.write(buffer.array());
        output.flush();
    }

    private static Packet read(DataInputStream input) throws Exception {
        int length = Integer.reverseBytes(input.readInt());
        byte[] payload = input.readNBytes(length);
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int id = buffer.getInt();
        int type = buffer.getInt();
        byte[] body = new byte[Math.max(0, length - 10)];
        buffer.get(body);
        return new Packet(id, type, new String(body, StandardCharsets.UTF_8));
    }

    private record Packet(int id, int type, String body) {
    }
}
