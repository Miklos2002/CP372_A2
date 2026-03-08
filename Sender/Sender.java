import java.net.*;
import java.io.*;
import java.util.*;

public class Sender {

    private static final int PAYLOAD_SIZE = 124;

    public static void main(String[] args) {

        if (args.length < 5) {
            System.out.println("Usage:");
            System.out.println("java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            return;
        }

        try {

            String rcvIP = args[0];
            int rcvDataPort = Integer.parseInt(args[1]);
            int senderAckPort = Integer.parseInt(args[2]);
            String inputFile = args[3];
            int timeout = Integer.parseInt(args[4]);

            boolean useGBN = false;
            int windowSize = 1;

            if (args.length == 6) {
                useGBN = true;
                windowSize = Integer.parseInt(args[5]);
            }

            InetAddress receiverAddress = InetAddress.getByName(rcvIP);

            DatagramSocket sendSocket = new DatagramSocket();
            DatagramSocket ackSocket = new DatagramSocket(senderAckPort);

            ackSocket.setSoTimeout(timeout);

            long startTime = System.nanoTime();

            DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
            sendPacket(sendSocket, receiverAddress, rcvDataPort, sot);

            waitForAck(ackSocket, 0);

            FileInputStream fis = new FileInputStream(inputFile);

            List<DSPacket> packets = new ArrayList<>();

            int seq = 1;

            byte[] buffer = new byte[PAYLOAD_SIZE];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {

                byte[] payload = Arrays.copyOf(buffer, bytesRead);

                DSPacket dataPacket = new DSPacket(DSPacket.TYPE_DATA, seq, payload);
                packets.add(dataPacket);

                seq = (seq + 1) % 128;
            }

            fis.close();

            if (packets.size() == 0) {

                DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, 1, null);
                sendPacket(sendSocket, receiverAddress, rcvDataPort, eot);

                waitForAck(ackSocket, 1);

                printTime(startTime);
                sendSocket.close();
                ackSocket.close();
                return;
            }

            if (!useGBN) {

                for (DSPacket packet : packets) {

                    int timeoutCount = 0;

                    while (true) {

                        sendPacket(sendSocket, receiverAddress, rcvDataPort, packet);

                        try {

                            int ackSeq = receiveAck(ackSocket);

                            if (ackSeq == packet.getSeqNum()) {
                                break;
                            }

                        } catch (SocketTimeoutException e) {

                            timeoutCount++;

                            if (timeoutCount >= 3) {
                                System.out.println("Unable to transfer file.");
                                System.exit(0);
                            }
                        }
                    }
                }
            }

            else {

                int base = 0;
                int next = 0;
                int timeoutCount = 0;

                while (base < packets.size()) {

                    while (next < base + windowSize && next < packets.size()) {

                        int remaining = Math.min(4, packets.size() - next);

                        List<DSPacket> group = new ArrayList<>();

                        for (int i = 0; i < remaining; i++) {
                            group.add(packets.get(next + i));
                        }

                        if (group.size() == 4) {
                            group = ChaosEngine.permutePackets(group);
                        }

                        for (DSPacket p : group) {
                            sendPacket(sendSocket, receiverAddress, rcvDataPort, p);
                        }

                        next += group.size();
                    }

                    try {

                        int ackSeq = receiveAck(ackSocket);

                        int index = findPacketIndex(packets, ackSeq);

                        if (index >= base) {
                            base = index + 1;
                            timeoutCount = 0;
                        }

                    } catch (SocketTimeoutException e) {

                        timeoutCount++;

                        if (timeoutCount >= 3) {
                            System.out.println("Unable to transfer file.");
                            System.exit(0);
                        }

                        next = base;
                    }
                }
            }

            int lastSeq = packets.get(packets.size() - 1).getSeqNum();
            int eotSeq = (lastSeq + 1) % 128;

            DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
            sendPacket(sendSocket, receiverAddress, rcvDataPort, eot);

            waitForAck(ackSocket, eotSeq);

            printTime(startTime);

            sendSocket.close();
            ackSocket.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sendPacket(DatagramSocket socket, InetAddress addr, int port, DSPacket packet) throws IOException {

        byte[] data = packet.toBytes();

        DatagramPacket dp = new DatagramPacket(data, data.length, addr, port);

        socket.send(dp);
    }

    private static int receiveAck(DatagramSocket socket) throws IOException {

        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];

        DatagramPacket dp = new DatagramPacket(buf, buf.length);

        socket.receive(dp);

        DSPacket packet = new DSPacket(dp.getData());

        if (packet.getType() == DSPacket.TYPE_ACK) {
            return packet.getSeqNum();
        }

        return -1;
    }

    private static void waitForAck(DatagramSocket socket, int seq) throws IOException {

        while (true) {

            int ack = receiveAck(socket);

            if (ack == seq) {
                return;
            }
        }
    }

    private static int findPacketIndex(List<DSPacket> packets, int seq) {

        for (int i = 0; i < packets.size(); i++) {

            if (packets.get(i).getSeqNum() == seq) {
                return i;
            }
        }

        return -1;
    }

    private static void printTime(long startTime) {

        long endTime = System.nanoTime();

        double seconds = (endTime - startTime) / 1e9;

        System.out.printf("Total Transmission Time: %.2f seconds\n", seconds);
    }
}
