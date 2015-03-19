package topology;

import backtype.storm.Config;
import backtype.storm.StormSubmitter;
import backtype.storm.generated.AlreadyAliveException;
import backtype.storm.generated.InvalidTopologyException;
import backtype.storm.generated.StormTopology;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.tuple.Fields;

import java.io.FileNotFoundException;

import static topology.Constants.*;
import static topology.StormConfigManager.getInt;
import static topology.StormConfigManager.getString;
import static topology.StormConfigManager.readConfig;

/**
 * Created by Intern04 on 4/8/2014.
 */
public class tVLDTopBeta {

    //TODO: further improvement: a) re-design PatchProcessorBolt, this is too heavy loaded!
    // b) then avoid broadcast the whole frames, split the functions in PatchProcessorBolt.
    //

    public static void main(String args[]) throws InterruptedException, AlreadyAliveException, InvalidTopologyException, FileNotFoundException {
        if (args.length != 1) {
            System.out.println("Enter path to config file!");
            System.exit(0);
        }
        Config conf = readConfig(args[0]);

        String host = getString(conf, "redis.host");
        int port = getInt(conf, "redis.port");
        String queueName = getString(conf, "tVLDQueueName");

        TopologyBuilder builder = new TopologyBuilder();
        String spoutName = "tVLDSpout";
        String transName = "tVLDeTrans";
        String patchGenBolt = "tVLDPatchGen";
        String patchProcBolt = "tVLDPatchProc";
        String patchAggBolt = "tVLDPatchAgg";
        String redisFrameOut = "tVLDRedisFrameOut";

        builder.setSpout(spoutName, new tFrameSourceBeta(host, port, queueName), getInt(conf, spoutName + ".parallelism"))
                .setNumTasks(getInt(conf, spoutName + ".tasks"));

        builder.setBolt(transName, new tVLDTransBolt(), getInt(conf, transName + ".parallelism"))
                .shuffleGrouping(spoutName, RAW_FRAME_STREAM)
                .setNumTasks(getInt(conf, transName + ".tasks"));

        builder.setBolt(patchGenBolt, new tPatchGeneraterBeta(patchProcBolt), getInt(conf, patchGenBolt + ".parallelism"))
                .allGrouping(transName, RAW_FRAME_STREAM)
                .setNumTasks(getInt(conf, patchGenBolt + ".tasks"));

        builder.setBolt(patchProcBolt, new tPatchProcessorBeta(), getInt(conf, patchProcBolt + ".parallelism"))
                //.allGrouping(patchProcBolt, LOGO_TEMPLATE_UPDATE_STREAM)
                .directGrouping(patchGenBolt, PATCH_FRAME_STREAM)
                .setNumTasks(getInt(conf, patchProcBolt + ".tasks"));

        builder.setBolt(patchAggBolt, new tPatchAggregatorBeta(), getInt(conf, patchAggBolt + ".parallelism"))
                //.fieldsGrouping(patchProcBolt, DETECTED_LOGO_STREAM, new Fields(FIELD_FRAME_ID))
                .globalGrouping(patchProcBolt, DETECTED_LOGO_STREAM)
                .setNumTasks(getInt(conf, patchAggBolt + ".tasks"));

        builder.setBolt(redisFrameOut, new tRedisFrameAggregatorBeta(), getInt(conf, redisFrameOut + ".parallelism"))
                .globalGrouping(patchAggBolt, PROCESSED_FRAME_STREAM)
                .globalGrouping(transName, RAW_FRAME_STREAM)
                .setNumTasks(getInt(conf, redisFrameOut + ".tasks"));

        StormTopology topology = builder.createTopology();

        int numberOfWorkers = getInt(conf, "tVLDNumOfWorkers");
        conf.setNumWorkers(numberOfWorkers);
        conf.setMaxSpoutPending(getInt(conf, "tVLDMaxPending"));

        conf.setStatsSampleRate(1.0);
        //conf.registerSerialization(Serializable.Mat.class);

        StormSubmitter.submitTopology("tVLDTopBeta-1", conf, topology);

    }
}