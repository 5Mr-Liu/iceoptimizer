package dev.rlcraft.ice.optimizer.render.terrain;
import java.util.Random;
/** Exhaustive independent oracle for the experimental upload-time planner. */
public final class TerrainGapPlannerProbe {
    public static void main(String[] args) {
        int[] topology=TerrainFaceRanges.spanPlans();
        for(int mask=0;mask<128;mask++) {
            int plan=topology[mask],reconstructed=0,previous=-1,spans=plan>>>24;
            if(spans>4)throw new AssertionError("too many spans");
            for(int remaining=spans;remaining>0;remaining--,plan>>>=6) {
                int first=plan&7,last=(plan>>>3)&7;
                if(first<=previous||first>last||last>6)throw new AssertionError("invalid ordered span");
                for(int face=first;face<=last;face++)reconstructed|=1<<face;
                previous=last;
            }
            if(mask!=reconstructed)throw new AssertionError("span changed face mask");
        }
        Random random=new Random(20261012L);
        long checked=0;
        for(int trial=0;trial<1024;trial++) {
            int[] counts=new int[7];
            for(int i=0;i<7;i++)counts[i]=random.nextInt(4)==0?0:random.nextInt(1537)*4;
            for(int mask=1;mask<128;mask+=2) {
                int plan=TerrainFaceRanges.planMask(counts,mask);
                if((plan&mask)!=mask)throw new AssertionError("visible bucket omitted");
                long optimum=Long.MAX_VALUE;
                for(int candidate=1;candidate<128;candidate+=2)
                    if((candidate&mask)==mask)optimum=Math.min(optimum,cost(counts,candidate));
                if(cost(counts,plan)!=optimum)throw new AssertionError("non-optimal conservative mask");
                checked++;
            }
        }
        System.out.println("exhaustive_plans="+checked+" conservative=true minimum_estimated_cost=true fixed_draw_cost=2048 packed_span_topologies=128");
    }
    private static long cost(int[] count,int mask) {
        long cost=0;boolean previous=false;
        for(int face=0;face<7;face++)if(count[face]!=0) {
            boolean keep=(mask&(1<<face))!=0;
            if(keep)cost+=count[face]+(previous?0:2048);
            previous=keep;
        }
        return cost;
    }
}
