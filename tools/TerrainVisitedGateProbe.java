import java.util.*;

/** Tools-only model of OptiFine G5's neighbour/bounds/visited/frustum ordering.
 * Measures a proposed visited-first predicate, NOT the game or a production
 * replacement. Position/bounds reads must be certified side-effect-free before
 * this ordering can be used on actual transformed classes. */
public final class TerrainVisitedGateProbe {
    private static volatile long sink;
    private static final class Chunk {
        final int id,x,y,z; final Chunk[] neighbours=new Chunk[6];
        final int visibleFrom,contents; final boolean inFrustum;
        int stamp;
        Chunk(int id,int x,int y,int z,Random r) {
            this.id=id;this.x=x;this.y=y;this.z=z;
            visibleFrom=r.nextInt(64)|3; contents=r.nextInt(8);
            inFrustum=r.nextInt(20)!=0;
        }
    }
    private static final class World {
        final Chunk[] chunks,queue; final int[] paths,incoming,terrain,entities,tiles;
        final int width; int terrainCount,entityCount,tileCount;
        long bounds,seen;
        World(int width,long seed) {
            this.width=width; int height=16;
            chunks=new Chunk[width*width*height];queue=new Chunk[chunks.length];
            paths=new int[chunks.length];incoming=new int[chunks.length];
            terrain=new int[chunks.length];entities=new int[chunks.length];tiles=new int[chunks.length];
            Random r=new Random(seed);
            for(int y=0;y<height;y++)for(int z=0;z<width;z++)for(int x=0;x<width;x++) {
                int id=(y*width+z)*width+x;
                chunks[id]=new Chunk(id,(x-width/2)*16,y*16,(z-width/2)*16,r);
            }
            for(int y=0;y<height;y++)for(int z=0;z<width;z++)for(int x=0;x<width;x++) {
                Chunk c=chunks[(y*width+z)*width+x];
                if(y>0)c.neighbours[0]=chunks[c.id-width*width];
                if(y+1<height)c.neighbours[1]=chunks[c.id+width*width];
                if(z>0)c.neighbours[2]=chunks[c.id-width];
                if(z+1<width)c.neighbours[3]=chunks[c.id+width];
                if(x>0)c.neighbours[4]=chunks[c.id-1];
                if(x+1<width)c.neighbours[5]=chunks[c.id+1];
            }
        }
        void traverse(boolean visitedFirst,int frame,int seed,boolean fog,boolean many,
                      int originX,int originZ,int maxY,int distanceSq,boolean count) {
            terrainCount=entityCount=tileCount=0; bounds=seen=0;
            int read=0,write=1;queue[0]=chunks[seed];paths[0]=0;incoming[0]=-1;
            chunks[seed].stamp=frame;
            while(read<write) {
                Chunk current=queue[read];int path=paths[read],from=incoming[read++];
                if((current.contents&1)!=0)terrain[terrainCount++]=current.id;
                if((current.contents&2)!=0)entities[entityCount++]=current.id;
                if((current.contents&4)!=0)tiles[tileCount++]=current.id;
                for(int face=0;face<6;face++) {
                    if(many && ((path&(1<<(face^1)))!=0 || from>=0&&(current.visibleFrom&(1<<face))==0))continue;
                    Chunk next=current.neighbours[face];if(next==null)continue;
                    if(visitedFirst&&next.stamp==frame) {if(count)seen++;continue;}
                    if(count)bounds++;
                    if(next.y>maxY)continue;
                    if(fog) {int dx=originX-next.x,dz=originZ-next.z;if(dx*dx+dz*dz>distanceSq)continue;}
                    if(next.stamp==frame) {if(count)seen++;continue;}
                    next.stamp=frame;
                    if(!next.inFrustum)continue;
                    queue[write]=next;paths[write]=path|(1<<face);incoming[write]=face;write++;
                }
            }
            Arrays.fill(queue,0,write,null);
        }
    }
    public static void main(String[] args) {
        Random random=new Random(0x12928L);long originalBounds=0,candidateBounds=0,visited=0;
        for(int graph=0;graph<32;graph++) {
            int width=9+2*(graph%8);long seed=random.nextLong();
            World a=new World(width,seed),b=new World(width,seed);
            for(int sample=0;sample<128;sample++) {
                int frame=sample+1,start=random.nextInt(a.chunks.length);
                boolean fog=random.nextBoolean(),many=random.nextBoolean();
                int x=sample%11==0?Integer.MAX_VALUE:random.nextInt(512)-256;
                int z=sample%13==0?Integer.MIN_VALUE:random.nextInt(512)-256;
                int y=random.nextInt(288)-16,distance=16+random.nextInt(256);
                // Include pre-stamped nodes (as after seeding or prior traversal).
                if(sample%7==0)for(int i=0;i<a.chunks.length;i+=17)a.chunks[i].stamp=b.chunks[i].stamp=frame;
                a.traverse(false,frame,start,fog,many,x,z,y,distance*distance,true);
                b.traverse(true,frame,start,fog,many,x,z,y,distance*distance,true);
                equal(a.terrain,a.terrainCount,b.terrain,b.terrainCount);
                equal(a.entities,a.entityCount,b.entities,b.entityCount);
                equal(a.tiles,a.tileCount,b.tiles,b.tileCount);
                for(int i=0;i<a.chunks.length;i++)if(a.chunks[i].stamp!=b.chunks[i].stamp)
                    throw new AssertionError("visited stamp mismatch");
                originalBounds+=a.bounds;candidateBounds+=b.bounds;visited+=b.seen;
            }
        }
        System.out.printf(Locale.ROOT,"oracle_cases=4096 exact_ordered_terrain_entities_tiles_and_stamps=true bounds=%d->%d early_visited=%d%n",originalBounds,candidateBounds,visited);
        for(boolean many:new boolean[]{false,true})benchmark(many);
        System.out.println("tools_only=true actual_OptiFine_Mixin_equivalence_and_FPS=unproven sink="+sink);
    }
    private static void benchmark(boolean many) {
        World[] worlds={new World(31,73),new World(31,73)};int[] frame={1,1};
        int start=(8*31+15)*31+15;
        for(int i=0;i<2000;i++)for(int mode=0;mode<2;mode++)worlds[mode].traverse(mode!=0,frame[mode]++,start,true,many,0,0,240,240*240,false);
        double[][] times=new double[2][51];
        for(int round=0;round<51;round++)for(int step=0;step<2;step++) {
            int mode=(round+step)%2;long begin=System.nanoTime();
            for(int i=0;i<100;i++)worlds[mode].traverse(mode!=0,frame[mode]++,start,true,many,0,0,240,240*240,false);
            times[mode][round]=(System.nanoTime()-begin)/100e6;sink+=worlds[mode].terrainCount;
        }
        for(int mode=0;mode<2;mode++) {
            Arrays.sort(times[mode]);System.out.printf(Locale.ROOT,"model=%s portal_filter=%s cpu_p50_ms=%.6f cpu_p95_ms=%.6f%n",mode==0?"original_order":"visited_first",many,times[mode][25],times[mode][48]);
        }
    }
    private static void equal(int[] a,int n,int[] b,int m) {
        if(n!=m)throw new AssertionError("visible count mismatch");
        for(int i=0;i<n;i++)if(a[i]!=b[i])throw new AssertionError("visible order mismatch");
    }
}
