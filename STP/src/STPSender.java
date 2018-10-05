import javafx.util.Pair;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

public class STPSender {
    private int sendTime;
    private File folder = new File(System.getProperty("user.dir"));
    private File[] allFiles = folder.listFiles();
    private InetAddress IP;
    private int portNumber;
    private DatagramSocket socket;
    private String fileRequested;
    private DatagramPacket dataIn = new DatagramPacket(new byte[1000024], 1000024);
    private DatagramPacket dataOut = new DatagramPacket(new byte[1000024], 1000024);
    //set the initial sequence number to 2^31 - 1000000000 for lee-way, this will also have enough randomness
    private int sequenceNumber = 0;
    private int ackNumber;
    private STPPacketHeader header;
    private STPPacket packet;
    private ReadablePacket r;
    private boolean SYN = false;
    private boolean ACK = false;
    private boolean FIN = false;
    private boolean DUP = false;
    private InetAddress receiverIP;
    private int receiverPort;
    private Unreliability PLD;
    private int MWS;
    private int MSS;
    private float gamma;
    private ArrayList<ReadablePacket> filePackets = new ArrayList<ReadablePacket>();
    private volatile ArrayBlockingQueue<ReadablePacket> window;
    private volatile ArrayBlockingQueue<Pair<Integer, ReadablePacket>> retransmissions;
    private STPTimer timer = new STPTimer();
    private FileInputStream file;
    private volatile int windowIndex = 0;
    private int windowSize;
    private FileWriter logFile;
    private int estimatedRTT = 500;
    private int devRTT = 250;
    private PriorityQueue<Integer> dupAcks = new PriorityQueue<Integer>(3);

    public STPSender(String args[]) {
        try {
            this.IP = InetAddress.getByName(InetAddress.getLocalHost().getCanonicalHostName());
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        this.portNumber = 2000 + new Random().nextInt(60000);
        try {
            this.socket = new DatagramSocket(this.portNumber, this.IP);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (args[0].equals("localhost") || args[0].equals("127.0.0.1")) {
                this.receiverIP = InetAddress.getByName(InetAddress.getLocalHost().getHostAddress());
            } else {
                this.receiverIP = InetAddress.getByName(args[0]);
            }
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        this.receiverPort = Integer.parseInt(args[1]);
        dataOut.setAddress(this.receiverIP);
        dataOut.setPort(this.receiverPort);
        this.fileRequested = args[2];
        this.MWS = Integer.parseInt(args[3]);
        this.MSS = Integer.parseInt(args[4]);
        this.gamma = Float.parseFloat(args[5]);
        this.windowSize = Math.floorDiv(MWS, MSS);
        window = new ArrayBlockingQueue<>(windowSize);
        retransmissions = new ArrayBlockingQueue<>(windowSize);
        float pDrop = Float.parseFloat(args[6]);
        float pDuplicate = Float.parseFloat(args[7]);
        float pCorrupt = Float.parseFloat(args[8]);
        float pOrder = Float.parseFloat(args[9]);
        int maxOrder = Integer.parseInt(args[10]);
        float pDelay = Float.parseFloat(args[11]);
        float maxDelay = Float.parseFloat(args[12]);
        float seed = Float.parseFloat(args[13]);
        this.PLD = new Unreliability(pDrop, pDuplicate, pCorrupt, pOrder, maxOrder, pDelay, maxDelay, seed);
        try {
            this.logFile = new FileWriter("Sender Log.txt");
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            String s = String.format("%-15s %-10s %-10s %-15s %-15s %-15s %-15s %-15s\n", "snd/rcv", "time",
                    "type", "sequence", "payload size", "ack", "RTT", "window size");
            logFile.write(s);
            logFile.flush();
            s = "------------------------------------------------------------------------------------------------------------------\n";
            logFile.write(s);
            logFile.flush();

        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println(sequenceNumber);
    }

    public void operate() {
        //initiate the 3 way handshake
        timer.start();
        prepareFile();
        handshake();
        sendData();
        terminate();
        System.exit(0);
    }

    private void prepareFile() {
        if (!containsFile(fileRequested)) {
            System.out.println("The file requested does not exist in this directory");
            System.exit(1);
        } else {
            //we want to create a list of ready to send packets (since they are file data we want to turn off most flags)
            try {
                file = new FileInputStream(fileRequested);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            byte[] packetPayload = new byte[MSS];
            int read = 0;
            while (true) {
                try {
                    read = file.read(packetPayload);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (read <= 0) {
                    break;
                }
                //now we want to store all these payloads into packet
                header = new STPPacketHeader(checksum(packetPayload), sequenceNumber, 0, IP,
                        receiverIP, portNumber, receiverPort, SYN, ACK, FIN, DUP);
                sequenceNumber += MSS;
                packet = new STPPacket(header, packetPayload);
                r = new ReadablePacket(packet.getPacket());
                filePackets.add(r);
            }
        }
    }

    private void handshake() {
        SYN = true;
        header = new STPPacketHeader(0, filePackets.get(0).getSequenceNumber(), 0, IP,
                receiverIP, portNumber, receiverPort, SYN, ACK, FIN, DUP);
        packet = new STPPacket(header, new byte[0]);
        sendPacket(packet);
        logWrite(0, filePackets.get(0).getSequenceNumber(), 0, "snd", "S",(estimatedRTT + (int)(gamma)*devRTT));
        //now we want for SYN ACK back
        while (true) {
            try {
                socket.setSoTimeout(estimatedRTT + (int)(gamma)*devRTT);
                dataIn.setAddress(receiverIP);
                dataIn.setPort(receiverPort);
                socket.receive(dataIn);
                r = new ReadablePacket(dataIn);
                if (r.isSYN() && r.isACK()) {
                    ACK = true;
                    ackNumber = r.getSequenceNumber();
                    logWrite(0, r.getSequenceNumber(), 1, "rcv", "SA",(estimatedRTT + (int)(gamma)*devRTT));
                    break;
                }
            } catch (SocketTimeoutException e) {
                SYN = true;
                header = new STPPacketHeader(0, sequenceNumber, 0, IP,
                        receiverIP, portNumber, receiverPort, SYN, ACK, FIN, DUP);
                packet = new STPPacket(header, new byte[0]);
                sendPacket(packet);
                logWrite(0, filePackets.get(0).getSequenceNumber(), 0, "snd", "S",(estimatedRTT + (int)(gamma)*devRTT));
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
        //end handshake with the ACK
        header = new STPPacketHeader(0, sequenceNumber, 0, IP,
                receiverIP, portNumber, receiverPort, SYN, ACK, FIN, DUP);
        packet = new STPPacket(header, new byte[0]);
        sendPacket(packet);
        logWrite(0, filePackets.get(0).getSequenceNumber() + 1, ackNumber + 1, "snd", "A",(estimatedRTT + (int)(gamma)*devRTT));
        r.display();
    }

    private void sendData() {
        //sender thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    if (filePackets.size() == windowIndex) {
                        System.out.println(filePackets.size() + "Break");
                        break;
                    }
                    //if there is room inside our window we will transmit a window size from current index (based off last ACK)
                    if (window.remainingCapacity() > 0) {
                        if (retransmissions.size() > 0) {
                            try {
                                Pair<Integer,ReadablePacket> retransmit= retransmissions.take();
                                window.put(retransmit.getValue());
                                sendTime = (int) System.currentTimeMillis();
                                sendPacket(packet);
                                logWrite(dataOut.getLength() - HeaderValues.PAYLOAD_POSITION_IN_HEADER, filePackets.get(retransmit.getKey()).getSequenceNumber(), ackNumber, "snd/RXT", "D",calculateRTTWithNoChange());
                                continue;
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        packet = new STPPacket(filePackets.get(windowIndex));
                        try {
                            window.put(filePackets.get(windowIndex));
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        windowIndex++;
                        sendTime = (int) System.currentTimeMillis();
                        sendPacket(packet);
                        logWrite(dataOut.getLength() - HeaderValues.PAYLOAD_POSITION_IN_HEADER, filePackets.get(windowIndex - 1).getSequenceNumber(), ackNumber, "snd", "D",calculateRTTWithNoChange());

                    }
                }
            }
        }).start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    if (filePackets.size() == windowIndex) {
                        break;
                    }
                    try {
                        dataIn.setAddress(receiverIP);
                        dataIn.setPort(receiverPort);
                        socket.setSoTimeout(calculateRTT());
                        socket.receive(dataIn);
                        estimatedRTT = (int) System.currentTimeMillis() - sendTime;
                        r = new ReadablePacket(dataIn);
                        if (r.isACK()) {
                            for (ReadablePacket read : window) {
                                if (r.getAcknowledgemntNumber() == read.getSequenceNumber()) {
                                    //clearWindowBeforeLastAck(r.getAcknowledgemntNumber());
                                    ackNumber = r.getSequenceNumber();
                                    window.remove(read);
                                    logWrite(0, ackNumber, r.getAcknowledgemntNumber(), "rcv", "A",estimatedRTT);
                                    //filePackets.remove(read);
                                }
                            }
                            continue;
                        } else {
                            ReadablePacket retransmit = getNACKPacket(r);
                            int index = packetIndex(retransmit);
                            retransmissions.put(new Pair<Integer, ReadablePacket>(index, retransmit));

                        }
                    } catch (SocketTimeoutException e) {
                        //if socket times out means the first packet in window was dropped so re-transmit
                        try {
                            ReadablePacket retransmit = window.take();
                            System.out.println("========retransmit for " + retransmit.getSequenceNumber() + " ====================");
                            int index = packetIndex(retransmit);

                            retransmissions.put(new Pair<Integer, ReadablePacket>(index, retransmit));
                            for(ReadablePacket ree : window){
                                System.out.println(ree.getSequenceNumber());
                            }
                            System.out.println("=========================================================================");
                        } catch (InterruptedException e1) {
                            e1.printStackTrace();
                        }
                        System.out.println("time-out: Retransmission");
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();

        while (true) {
            if (filePackets.size() == windowIndex) {
                break;
            }
            try {

                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void terminate() {
        //send out the FIN
        try {
            socket.setSoTimeout(0);
        } catch (SocketException e) {
            e.printStackTrace();
        }
        FIN = true;
        DUP = false;
        ACK = false;
        header = new STPPacketHeader(0, sequenceNumber, 0, IP,
                receiverIP, portNumber, receiverPort, SYN, ACK, FIN, DUP);
        packet = new STPPacket(header, new byte[0]);
        sendPacket(packet);
        //now wait for the FIN ACK
        while (true) {
            try {
                socket.receive(dataIn);
            } catch (IOException e) {
                e.printStackTrace();
            }
            r = new ReadablePacket(dataIn);
            if (r.isFIN() && r.isACK()) {
                break;
            }
        }
        //now wait for the FIN
        while (true) {
            try {
                socket.receive(dataIn);
            } catch (IOException e) {
                e.printStackTrace();
            }
            r = new ReadablePacket(dataIn);
            if (r.isFIN() && !r.isACK()) {
                break;
            }
        }
        //send back an FIN ACK to the client
        FIN = true;
        ACK = true;
        header = new STPPacketHeader(0, sequenceNumber, 0, IP,
                receiverIP, portNumber, receiverPort, SYN, ACK, FIN, DUP);
        packet = new STPPacket(header, new byte[0]);
        sendPacket(packet);
    }

    private boolean containsFile(String fileName) {
        //scan through directory
        for (File file : allFiles)
            if (file.getName().equals(fileName))
                return true;
        //otherwise return false if no files match
        return false;
    }

    private int checksum(byte[] payload) {
        int sum = 0;
        for (byte byteData : payload) {
            sum += (int) byteData;
        }
        sum = ~sum;
        return sum;
    }


    private void sendPacket(STPPacket p) {
        dataOut = p.getPacket();
        dataOut.setAddress(receiverIP);
        dataOut.setPort(receiverPort);
        try {
            socket.send(dataOut);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public void logWrite(int length, int sequenceNumber, int ackNumber, String sndOrReceive, String status,int timeOut) {
        float timePassed = timer.timePassed() / 1000;
        String s = String.format("%-15s %-10s %-10s %-15s %-15s %-15s %-15s %-15s\n", sndOrReceive
                , timePassed, status, sequenceNumber, length, ackNumber, timeOut, window.size());
        try {
            logFile.write(s);
            logFile.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


    public int packetIndex(ReadablePacket r) {
        return filePackets.indexOf(r);
    }

    public ReadablePacket getNACKPacket(ReadablePacket r) {
        for (ReadablePacket read : filePackets) {
            if (read.getSequenceNumber() == r.getAcknowledgemntNumber()) {
                return read;
            }
        }
        return null;
    }

    public void clearWindowBeforeLastAck(int ack){
        for(ReadablePacket r : window){
            if(r.getAcknowledgemntNumber() < ack + MSS){
                //logWrite(0,1,r.getSequenceNumber(),"rcv","A");
                window.remove(r);
            }
        }
    }

    public int calculateRTT(){

        estimatedRTT = (int) ((1-0.25)*estimatedRTT);
        estimatedRTT += (int) (0.25)*((System.currentTimeMillis() - sendTime));

        devRTT = (int)((1-0.25)*devRTT);
        int subtract = (int)((System.currentTimeMillis() - sendTime));
        devRTT += (int)(0.25*(Math.abs(subtract-estimatedRTT)));
        return (estimatedRTT + (int)this.gamma*devRTT);
    }

    public int calculateRTTWithNoChange(){
        int tmpEstimatedRTT = estimatedRTT;
        tmpEstimatedRTT = (int) ((1-0.25)*estimatedRTT);
        tmpEstimatedRTT += (int) (0.25)*((System.currentTimeMillis() - sendTime));
        int tmpDevRTT = devRTT;
        tmpDevRTT = (int)((1-0.25)*devRTT);
        int subtract = (int)((System.currentTimeMillis() - sendTime));
        tmpDevRTT += (int)(0.25*(Math.abs(subtract-estimatedRTT)));
        return (tmpEstimatedRTT + (int)this.gamma*tmpDevRTT);
    }
}
