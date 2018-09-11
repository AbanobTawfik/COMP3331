import java.util.*;

public class PacketBuffer {
    private Comparator<ReadablePacket> c = new PacketComparator();
    private PriorityQueue<ReadablePacket> buffer;
    private int maxCapacity;
    //methods for the packet_buffer

    public PacketBuffer(int maxCapacity) {
        this.buffer = new PriorityQueue<ReadablePacket>(maxCapacity, this.c);
        this.maxCapacity = maxCapacity;
    }

    //method to read the first element value in buffer
    public int peek_sequence_number(){
        if(!buffer.isEmpty())
            return buffer.peek().getSequenceNumber();
        return -1;
    }

    public void add(ReadablePacket p){
        buffer.offer(p);
    }

    public ReadablePacket remove(){
        return buffer.poll();
    }

    public int spaceRemaining(){
        return this.maxCapacity - this.buffer.size();
    }

    public void addConditionally(ArrayList<ReadablePacket> src){
        while(src.get(src.size()).getSequenceNumber() > peek_sequence_number()){
            src.add(remove());
        }
    }

}