package xmu.network.others;

import java.util.Objects;

public class AS {
    private int front;
    private int end;
    private boolean isLogic;
    public AS(String asPath){
        if(asPath.contains(".")){
            String[] parts=asPath.split("\\.");
            this.front=Integer.parseInt(parts[0]);
            this.end=Integer.parseInt(parts[1]);
            if(this.front == 0){
                this.isLogic=true;
            }
            else
                this.isLogic=false;
        }
        else {
            this.front=Integer.parseInt(asPath);
            this.end= 0;
        }
    }

    public boolean isLogic() {
        return isLogic;
    }

    @Override
    public String toString() {
        if (this.end != 0) return front + "." + end;
        else return Integer.toString(front);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        AS asNumber = (AS) obj;
        return front == asNumber.front &&end==asNumber.end;
    }

    @Override
    public int hashCode() {
        return Objects.hash(front, end);
    }
}
