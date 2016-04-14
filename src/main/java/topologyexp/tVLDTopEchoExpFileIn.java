package topologyexp;

import backtype.storm.Config;
import backtype.storm.StormSubmitter;
import backtype.storm.generated.AlreadyAliveException;
import backtype.storm.generated.AuthorizationException;
import backtype.storm.generated.InvalidTopologyException;
import backtype.storm.generated.StormTopology;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.tuple.Fields;
import showTraj.RedisFrameOutput;
import topology.*;
import util.ConfigUtil;

import java.io.FileNotFoundException;
import java.util.List;

import static tool.Constants.*;
import static topology.StormConfigManager.*;

/**
 * Created by Tom Fu, this version is through basic testing.
 * In the delta version, we enables the feature of supporting the multiple logo input,
 * When the setting in the configuration file includes multiple logo image files,
 * it automatically creates corresponding detector instance
 *
 * When the number of logo image file = 1, it turn back to the gamma version.
 * This version is still preliminary and need more improvement
 *
 * Note: we in this version's patchProc bolt (tPatchProcessorDelta), uses the StormVideoLogoDetectorBeta class, not the normal one StormVideoLogoDetector!!!
 * Through testing, when sampleFrame = 4, it supports up to 25 fps.
 *
 * Updated on April 29, the way to handle frame sampling issue is changed, this is pre-processed by the spout not to
 * send out unsampled frames to the patch generation bolt.
 */
public class tVLDTopEchoExpFileIn {

    //TODO: double check the new sampling handling approach.
    // emit two stream for the raw frame, one for drawBolt, another one depends on sample to the patchGen bolt
    // b) then avoid broadcast the whole frames, split the functions in PatchProcessorBolt.
    //

    public static void main(String args[]) throws InterruptedException, AlreadyAliveException, InvalidTopologyException, FileNotFoundException, AuthorizationException {
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
        String patchDrawBolt = "tVLDPatchDraw";
        String redisFrameOut = "tVLDRedisFrameOut";

        builder.setSpout(spoutName, new tomFrameSpoutResize(), getInt(conf, spoutName + ".parallelism"))
                .setNumTasks(getInt(conf, spoutName + ".tasks"));

        builder.setBolt(transName, new tVLDTransBolt(), getInt(conf, transName + ".parallelism"))
                .shuffleGrouping(spoutName, SAMPLE_FRAME_STREAM)
                .setNumTasks(getInt(conf, transName + ".tasks"));

        builder.setBolt(patchGenBolt, new tPatchGeneraterGamma(), getInt(conf, patchGenBolt + ".parallelism"))
                .shuffleGrouping(transName, PATCH_FRAME_STREAM)
                .setNumTasks(getInt(conf, patchGenBolt + ".tasks"));

        builder.setBolt(patchProcBolt, new tPatchProcessorEcho(), getInt(conf, patchProcBolt + ".parallelism"))
                .allGrouping(patchProcBolt, LOGO_TEMPLATE_UPDATE_STREAM)
                .shuffleGrouping(patchGenBolt, PATCH_FRAME_STREAM)
                .setNumTasks(getInt(conf, patchProcBolt + ".tasks"));

        builder.setBolt(patchAggBolt, new tPatchAggSampleDelta(), getInt(conf, patchAggBolt + ".parallelism"))
                .globalGrouping(patchProcBolt, DETECTED_LOGO_STREAM)//todo: double check, make sure the output of last bolt has FrameID field!!
                //todo: still have problem when sample rate is even number!!!
                //.fieldsGrouping(patchProcBolt, DETECTED_LOGO_STREAM, new Fields(FIELD_FRAME_ID))
                .setNumTasks(getInt(conf, patchAggBolt + ".tasks"));

        builder.setBolt(patchDrawBolt, new tDrawPatchDelta(), getInt(conf, patchDrawBolt + ".parallelism"))
                .fieldsGrouping(patchAggBolt, PROCESSED_FRAME_STREAM, new Fields(FIELD_FRAME_ID))
                .fieldsGrouping(spoutName, RAW_FRAME_STREAM, new Fields(FIELD_FRAME_ID))
                .setNumTasks(getInt(conf, patchDrawBolt + ".tasks"));

        builder.setBolt(redisFrameOut, new RedisFrameOutput(), getInt(conf, redisFrameOut + ".parallelism"))
                .globalGrouping(patchDrawBolt, STREAM_FRAME_DISPLAY)
                .setNumTasks(getInt(conf, redisFrameOut + ".tasks"));

        StormTopology topology = builder.createTopology();

        int numberOfWorkers = getInt(conf, "tVLDNumOfWorkers");
        conf.setNumWorkers(numberOfWorkers);
        conf.setMaxSpoutPending(getInt(conf, "tVLDMaxPending"));

        conf.setStatsSampleRate(1.0);
        //conf.registerSerialization(Serializable.Mat.class);
        int sampleFrames = getInt(conf, "sampleFrames");
        int W = ConfigUtil.getInt(conf, "width", 640);
        int H = ConfigUtil.getInt(conf, "height", 480);

        List<String> templateFiles = getListOfStrings(conf, "originalTemplateFileNames");

        StormSubmitter.submitTopology("tVLDEchoFIn-exp-s" + sampleFrames + "-" + W + "-" + H + "-L" + templateFiles.size(), conf, topology);

    }
}
