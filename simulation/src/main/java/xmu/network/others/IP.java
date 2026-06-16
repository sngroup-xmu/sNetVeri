package xmu.network.others;

import java.util.Arrays;

public class IP implements Comparable<IP>{
    public static final IP NULL = new IP("0.0.0.0");
    public static final IP LOOPBACK = new IP("127.0.0.1");
    public static final IP MAX = new IP("255.255.255.255");
    byte[] address;

    public IP (String ip){
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid IP address: " + ip);
        }
        address = new byte[4];
        for (int i = 0; i < 4; i++) {
            int part = Integer.parseInt(parts[i]);
            if (part < 0 || part > 255) {
                throw new IllegalArgumentException("Invalid IP address: " + ip);
            }
            address[i] = (byte) part;
        }
    }

    public IP(byte[] address) {
        this.address = address;
    }

    public IP(IP ip){
        this.address = ip.getAddress().clone();
    }

    public long ipToLong() { //转成long
        long result = 0;
        for (byte b : address) {
            result = (result << 8) | (b & 0xFF);
        }
        return result;
    }
    public byte[] getAddress() {
        return address;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        IP other = (IP) obj;
        if (other.address.length != this.address.length) {
            return false;
        }
        for (int i = 0; i < this.address.length; i++) {
            if (this.address[i] != other.address[i]) {
                return false;
            }
        }
        return true;
    }
    @Override
    public int hashCode() {
        return Arrays.hashCode(address);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            int part = address[i] & 0xFF;
            sb.append(part);
            if (i < 3) {
                sb.append(".");
            }
        }
        return sb.toString();
    }
    @Override
    public int compareTo(IP other) {
        byte[] a1 = this.address;
        byte[] a2 = other.address;
        int len = Math.min(a1.length, a2.length);
        for (int i = 0; i < len; i++) {
            int b1 = a1[i] & 0xFF;
            int b2 = a2[i] & 0xFF;
            if (b1 != b2) {
                return Integer.compare(b1, b2);
            }
        }
        return Integer.compare(a1.length, a2.length);
    }
}
