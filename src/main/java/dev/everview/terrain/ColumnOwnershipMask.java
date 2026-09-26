package dev.everview.terrain;

/** Bit-identical CPU/shader ownership map. World chunk coordinates, including negatives. */
public final class ColumnOwnershipMask {
    public static final int WIDTH=256, WORDS=WIDTH*WIDTH/32;
    private final int[] words=new int[WORDS];private int originX,originZ,count;
    public void begin(int cameraChunkX,int cameraChunkZ){originX=cameraChunkX-WIDTH/2;originZ=cameraChunkZ-WIDTH/2;java.util.Arrays.fill(words,0);count=0;}
    public void own(int chunkX,int chunkZ){int x=chunkX-originX,z=chunkZ-originZ;if(x<0||z<0||x>=WIDTH||z>=WIDTH)return;int bit=z*WIDTH+x;int flag=1<<(bit&31);if((words[bit>>5]&flag)==0){words[bit>>5]|=flag;count++;}}
    public boolean owns(int chunkX,int chunkZ){int x=chunkX-originX,z=chunkZ-originZ;if(x<0||z<0||x>=WIDTH||z>=WIDTH)return false;int bit=z*WIDTH+x;return (words[bit>>5]&(1<<(bit&31)))!=0;}
    public int originBlockX(){return originX*16;}public int originBlockZ(){return originZ*16;}
    public int[] words(){return words;}public int count(){return count;}
}
