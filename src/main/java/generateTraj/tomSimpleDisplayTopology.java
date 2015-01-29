package generateTraj;

import backtype.storm.Config;
import backtype.storm.StormSubmitter;
import backtype.storm.generated.AlreadyAliveException;
import backtype.storm.generated.InvalidTopologyException;
import backtype.storm.generated.StormTopology;
import backtype.storm.topology.TopologyBuilder;

import showTraj.RedisImageFrameOutput;
import tool.FrameImplImageSource;

import java.io.FileNotFoundException;

import static tool.Constant.STREAM_FRAME_OUTPUT;
import static topology.StormConfigManager.*;

/**
 * Created by Tom Fu on Jan 29, 2015.
 */
public class tomSimpleDisplayTopology {

    public static void main(String args[]) throws InterruptedException, AlreadyAliveException, InvalidTopologyException, FileNotFoundException {
        if (args.length != 1) {
            System.out.println("Enter path to config file!");
            System.exit(0);
        }
        Config conf = readConfig(args[0]);
        int numberOfWorkers = getInt(conf, "st-numberOfWorkers");
        //int numberOfAckers = getInt(conf, "numberOfAckers");

        TopologyBuilder builder = new TopologyBuilder();

        String host = getString(conf, "redis.host");
        int port = getInt(conf, "redis.port");
        String queueName = getString(conf, "redis.sourceQueueName");

        builder.setSpout("fSource", new FrameImplImageSource(host, port, queueName), getInt(conf, "GenerateTrajSpout.parallelism"))
                .setNumTasks(getInt(conf, "GenerateTrajSpout.tasks"));


        builder.setBolt("fOut", new RedisImageFrameOutput(), getInt(conf, "GenerateTrajFrameOutput.parallelism"))
                .globalGrouping("fSource", STREAM_FRAME_OUTPUT)
                .setNumTasks(getInt(conf, "GenerateTrajFrameOutput.tasks"));

        StormTopology topology = builder.createTopology();

        conf.setNumWorkers(numberOfWorkers);
        //conf.setNumAckers(numberOfAckers);
        conf.setMaxSpoutPending(getInt(conf, "st-MaxSpoutPending"));

        StormSubmitter.submitTopology("tomSimpleDisplayTopology", conf, topology);

    }
}
