import java.util.*;

/** Tools-only numerical model of the two OTG fog sample loops. Checks cached
 * aggregates against separately ordered original color/density arithmetic.
 * Real handler injection, callback order and world/config lifetimes are NOT
 * certified by this probe. Never used by the production artifact. */
public final class OtgFogMemoProbe {
    private static final class Entry {
        int color;float density;
        Entry(int color,float density){this.color=color;this.density=density;}
    }
    private static final class Sum {
        double r,g,b,weight;float density,densityWeight;boolean hasFog;
    }
    private static volatile long sink;
    private static double difference(double position,int floor,int delta,int radius) {
        if(delta==-radius)return 1D-(position-floor);
        if(delta==radius)return position-floor;
        return -1D;
    }
    private static Sum original(Entry[] table,short[][] ids,double x,double z,int radius) {
        Sum sum=new Sum();int floorX=(int)Math.floor(x),floorZ=(int)Math.floor(z);
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++) {
            Entry e=table[ids[dx+radius][dz+radius]];int color=e.color;
            if(color==0)continue;
            double r=(color&0xff0000)>>16,g=(color&0xff00)>>8,b=color&255;float w=1F;
            double a=difference(x,floorX,dx,radius),c=difference(z,floorZ,dz,radius);
            if(a>=0D){r*=a;g*=a;b*=a;w=(float)(w*a);}
            if(c>=0D){r*=c;g*=c;b*=c;w=(float)(w*c);}
            sum.r+=r;sum.g+=g;sum.b+=b;sum.weight+=w;
        }
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++) {
            Entry e=table[ids[dx+radius][dz+radius]];
            if(e.color==0)continue;
            sum.hasFog=true;float d=1F-e.density,w=1F;
            double a=difference(x,floorX,dx,radius),c=difference(z,floorZ,dz,radius);
            if(a>=0D){d=(float)(d*a);w=(float)(w*a);}
            if(c>=0D){d=(float)(d*c);w=(float)(w*c);}
            sum.density+=d;sum.densityWeight+=w;
        }
        return sum;
    }
    private static final class Memo {
        private final short[] cells=new short[69*69];
        private final Entry[] refs=new Entry[2048];
        private final int[] colors=new int[2048],densityBits=new int[2048],indices=new int[2048],seen=new int[2048];
        private int generation,unique,radius;private long xBits,zBits;
        private Object world;private Entry[] table;private boolean valid;private Sum sum;
        long hits,misses;
        Sum evaluate(Object nextWorld,Entry[] nextTable,short[][] ids,double x,double z,int nextRadius,boolean forceRefresh) {
            if(forceRefresh||!Double.isFinite(x)||!Double.isFinite(z)||nextRadius<0||nextRadius>34
                ||nextTable==null||nextTable.length>2048||ids==null||ids.length<nextRadius*2+1) {invalidate();return null;}
            int width=nextRadius*2+1;
            boolean hit=valid&&world==nextWorld&&table==nextTable&&radius==nextRadius
                &&xBits==Double.doubleToRawLongBits(x)&&zBits==Double.doubleToRawLongBits(z);
            int cell=0;
            for(int row=0;row<width;row++) {
                if(ids[row]==null||ids[row].length<width){invalidate();return null;}
                for(int col=0;col<width;col++) {
                    int id=ids[row][col];
                    if(id<0||id>=nextTable.length||nextTable[id]==null){invalidate();return null;}
                    if(hit&&cells[cell]!=id)hit=false;cell++;
                }
            }
            if(hit)for(int i=0;i<unique;i++) {
                int id=indices[i];Entry e=nextTable[id];
                if(e!=refs[id]||e.color!=colors[id]||Float.floatToRawIntBits(e.density)!=densityBits[id]){hit=false;break;}
            }
            if(hit){hits++;return sum;}
            misses++;invalidate();
            if(++generation==0){Arrays.fill(seen,0);generation=1;}
            int floorX=(int)Math.floor(x),floorZ=(int)Math.floor(z);Sum next=new Sum();cell=0;
            for(int row=0;row<width;row++)for(int col=0;col<width;col++) {
                int id=ids[row][col];Entry e=nextTable[id];cells[cell++]=(short)id;
                if(seen[id]!=generation){seen[id]=generation;indices[unique++]=id;refs[id]=e;colors[id]=e.color;densityBits[id]=Float.floatToRawIntBits(e.density);}
                int color=e.color;if(color==0)continue;
                double r=(color&0xff0000)>>16,g=(color&0xff00)>>8,b=color&255;
                float d=1F-e.density,w=1F;
                double a=difference(x,floorX,row-nextRadius,nextRadius),c=difference(z,floorZ,col-nextRadius,nextRadius);
                if(a>=0D){r*=a;g*=a;b*=a;d=(float)(d*a);w=(float)(w*a);}
                if(c>=0D){r*=c;g*=c;b*=c;d=(float)(d*c);w=(float)(w*c);}
                next.r+=r;next.g+=g;next.b+=b;next.weight+=w;
                next.density+=d;next.densityWeight+=w;next.hasFog=true;
            }
            world=nextWorld;table=nextTable;radius=nextRadius;xBits=Double.doubleToRawLongBits(x);zBits=Double.doubleToRawLongBits(z);
            sum=next;valid=true;return sum;
        }
        private void invalidate() {
            for(int i=0;i<unique;i++)refs[indices[i]]=null;
            unique=0;valid=false;world=null;table=null;sum=null;
        }
    }
    public static void main(String[] args) {
        Random random=new Random(0x12930L);Entry[] table=new Entry[2048];
        for(int i=0;i<table.length;i++)table[i]=new Entry(i%5==0?0:random.nextInt(),random.nextFloat());
        short[][] ids=new short[69][69];for(short[] row:ids)for(int i=0;i<row.length;i++)row[i]=(short)random.nextInt(64);
        Memo memo=new Memo();Object world=new Object();double x=12.375,z=-5.8125;
        for(int round=0;round<2048;round++) {
            int id=random.nextInt(64);
            switch(round%8) {
                case 0:table[id].color=random.nextInt();break;
                case 1:table[id].density=Float.intBitsToFloat(random.nextInt());break;
                case 2:table[id]=new Entry(random.nextInt(),random.nextFloat());break;
                case 3:ids[random.nextInt(69)][random.nextInt(69)]=(short)random.nextInt(64);break;
                case 4:x=random.nextDouble()*256-128;z=random.nextDouble()*256-128;break;
                case 5:table=table.clone();break;
                case 6:world=new Object();break;
                default:break;
            }
            int radius=(round/16)%35;
            equal(original(table,ids,x,z,radius),memo.evaluate(world,table,ids,x,z,radius,false));
            equal(original(table,ids,x,z,radius),memo.evaluate(world,table,ids,x,z,radius,false));
        }
        if(memo.evaluate(world,table,ids,x,z,34,true)!=null)throw new AssertionError("force refresh must decline");
        short old=ids[0][0];ids[0][0]=-1;
        if(memo.evaluate(world,table,ids,x,z,34,false)!=null)throw new AssertionError("unknown biome must decline");
        ids[0][0]=old;Entry entry=table[old];table[old]=null;
        if(memo.evaluate(world,table,ids,x,z,34,false)!=null)throw new AssertionError("missing config must decline");
        table[old]=entry;
        System.out.println("oracle_raw_bit_comparisons=4096 mutable_grid_config_identity_fields_world_table_radius_position=passed unknown_or_force_refresh=declined");
        // Restore ordinary densities for a timing-only, stable 69x69 grid.
        for(Entry value:table)value.density=.35F;
        for(int reuse:new int[]{1,8,1000000})benchmark(table,ids,world,reuse);
        System.out.println("tools_only=true actual_handler_callbacks_side_effects_lifetimes_and_FPS=unproven sink="+sink);
    }
    private static void benchmark(Entry[] table,short[][] ids,Object world,int reuse) {
        Memo memo=new Memo();double[][] times=new double[2][31];long[] iterations={0,0};
        for(int warm=0;warm<300;warm++){original(table,ids,12.375,-5.8125,34);memo.evaluate(world,table,ids,12.375,-5.8125,34,false);}
        for(int round=0;round<31;round++)for(int step=0;step<2;step++) {
            int mode=(round+step)%2;long begin=System.nanoTime();
            for(int n=0;n<64;n++) {
                double x=12D+((iterations[mode]++/reuse)%16)/16D;
                Sum sum=mode==0?original(table,ids,x,-5.8125,34):memo.evaluate(world,table,ids,x,-5.8125,34,false);
                sink=Double.doubleToRawLongBits(sum.r)^Float.floatToRawIntBits(sum.density);
            }
            times[mode][round]=(System.nanoTime()-begin)/64e6;
        }
        for(int mode=0;mode<2;mode++){Arrays.sort(times[mode]);System.out.printf(Locale.ROOT,"reuse=%d mode=%s cpu_p50_ms=%.6f cpu_p95_ms=%.6f%n",reuse,mode==0?"original_loops":"validated_memo",times[mode][15],times[mode][29]);}
        System.out.println("memo_hits="+memo.hits+" memo_misses="+memo.misses);
    }
    private static void equal(Sum a,Sum b) {
        if(b==null||Double.doubleToRawLongBits(a.r)!=Double.doubleToRawLongBits(b.r)
            ||Double.doubleToRawLongBits(a.g)!=Double.doubleToRawLongBits(b.g)
            ||Double.doubleToRawLongBits(a.b)!=Double.doubleToRawLongBits(b.b)
            ||Double.doubleToRawLongBits(a.weight)!=Double.doubleToRawLongBits(b.weight)
            ||Float.floatToRawIntBits(a.density)!=Float.floatToRawIntBits(b.density)
            ||Float.floatToRawIntBits(a.densityWeight)!=Float.floatToRawIntBits(b.densityWeight)
            ||a.hasFog!=b.hasFog)throw new AssertionError("fog aggregate bits differ");
    }
}
