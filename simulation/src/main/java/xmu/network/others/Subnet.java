package xmu.network.others;
import java.util.Arrays;
import java.util.Objects;

public class Subnet implements Comparable<Subnet> {
    private IP baseAddress; // 网段的起始地址（网络地址）
    private byte prefixLength; // 网段的前缀长度
    public static final byte LENGTH_MAX = 32;
    public Subnet(String prefix) {
        String[] parts = prefix.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid Subnet: " + prefix);
        }
        this.baseAddress = new IP(parts[0]);
        this.prefixLength = Byte.parseByte(parts[1]);
        validatePrefixLength(this.prefixLength);
    }

    public Subnet(IP baseAddress, byte prefixLength) {
        this.baseAddress = baseAddress;
        this.prefixLength = prefixLength;
    }
    public Subnet(Subnet subnet) {
        this.baseAddress=new IP(subnet.baseAddress);
        this.prefixLength=subnet.prefixLength;
    }
    private static void validatePrefixLength(byte prefixLength) {
        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("Invalid Subnet: " + prefixLength);
        }
    }
    public Subnet formalNetworkAddress() {
        byte[] networkBytes = new byte[baseAddress.getAddress().length];

        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;
        // 中文注释：网络地址必须按真实 prefixLength 归零，不能写死成 /31 或 /32。
        for (int i = 0; i < fullBytes; i++) {
            networkBytes[i] = baseAddress.getAddress()[i];
        }

        if (remainingBits > 0) {
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            networkBytes[fullBytes] = (byte) (baseAddress.getAddress()[fullBytes] & mask);
        }

        IP ip = new IP(networkBytes);
        Subnet network=new Subnet(ip,prefixLength);
        return network;
    }

    public boolean contains(Subnet other, byte greaterEq, byte lessEq) {
        // 1. 校验 prefix 范围
        validatePrefixLength(greaterEq);
        validatePrefixLength(lessEq);

        if (greaterEq > lessEq) {
            throw new IllegalArgumentException("Invalid range: greaterEq > lessEq");
        }

        // 2. 判断 other 的 prefix 是否在范围内
        if (other.prefixLength < greaterEq || other.prefixLength > lessEq) {
            return false;
        }

        // 3. 必须比当前 subnet 更具体或相等
        if (other.prefixLength < this.prefixLength) {
            return false;
        }

        // 4. 统一成标准网络地址（避免传入不是 network address）
        Subnet thisNet = this.formalNetworkAddress();
        Subnet otherNet = other.formalNetworkAddress();

        byte[] thisBytes = thisNet.baseAddress.getAddress();
        byte[] otherBytes = otherNet.baseAddress.getAddress();

        int fullBytes = this.prefixLength / 8;
        int remainingBits = this.prefixLength % 8;

        // 5. 比较完整字节
        for (int i = 0; i < fullBytes; i++) {
            if (thisBytes[i] != otherBytes[i]) {
                return false;
            }
        }

        // 6. 比较剩余 bits
        if (remainingBits > 0) {
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;

            if ((thisBytes[fullBytes] & mask) != (otherBytes[fullBytes] & mask)) {
                return false;
            }
        }

        return true;
    }

    public byte getPrefixLength() {
        return prefixLength;
    }
    public IP getBaseAddress() {
        return baseAddress;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Subnet subnet = (Subnet) o;
        return Arrays.equals(baseAddress.getAddress(),subnet.baseAddress.getAddress()) &&
                prefixLength == subnet.prefixLength;
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseAddress, prefixLength);
    }


    @Override
    public String toString() {
        return baseAddress.toString() + "/" + prefixLength;
    }


    @Override
    public int compareTo(Subnet other) {
        int prefixCompare = Byte.compare(other.prefixLength, this.prefixLength);
        if (prefixCompare != 0) {
            return prefixCompare;
        }

        Subnet otherSubnet = other.formalNetworkAddress();
        Subnet thisSubnet = this.formalNetworkAddress();
        int cmp = thisSubnet.baseAddress.compareTo(otherSubnet.baseAddress); // IP越小越靠前
        return cmp;
    }
}
