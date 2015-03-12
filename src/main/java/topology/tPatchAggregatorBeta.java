package topology;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import logodetection.Debug;
import org.bytedeco.javacpp.opencv_core;

import java.util.*;

import static topology.Constants.*;

/**
 * Created by Intern04 on 5/8/2014.
 */
public class tPatchAggregatorBeta extends BaseRichBolt {
    OutputCollector collector;

    /* Keeps track on which patches of the certain frame have already been received */
    Map< Integer, HashSet<Serializable.Rect> > frameAccount;

    /* Contains the list of logos found found on a given frame */
    Map< Integer, List<Serializable.Rect> > foundRectAccount;
    @Override

    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        this.collector = outputCollector;
        frameAccount = new HashMap<>();
        foundRectAccount = new HashMap<>();

        opencv_core.IplImage fk = new opencv_core.IplImage();
    }

    //Fields("frameId", "framePatchIdentifier", "foundRect", "patchCount"));
    @Override
    public void execute(Tuple tuple) {
        Serializable.PatchIdentifier patchIdentifier = (Serializable.PatchIdentifier)tuple.getValueByField(FIELD_HOST_PATCH);
        int patchCount              = tuple.getIntegerByField(FIELD_PATCH_COUNT);
        Serializable.Rect foundRect = (Serializable.Rect)tuple.getValueByField(FIELD_DETECTED_RECT);

        int frameId = patchIdentifier.frameId;
        System.out.println("Received detected path of frame; " + frameId + ", at time: " + System.currentTimeMillis());
        /* Updating the list of detected logos on the frame */
        if (foundRect != null) {
            foundRectAccount.computeIfAbsent(frameId, k->new ArrayList<>()).add(foundRect);
        }

        /* Updating the account on how many patches of a frame are received */
//        if (!frameAccount.containsKey(frameId))
//            frameAccount.put(frameId, new HashSet<>());
//        frameAccount.get(frameId).add(patchIdentifier.roi);
        frameAccount.computeIfAbsent(frameId, k->new HashSet<>()).add(patchIdentifier.roi);

        /* If all patches of this frame are collected proceed to the frame aggregator */
        if (frameAccount.get(frameId).size() == patchCount) {
            if (Debug.topologyDebugOutput)
                System.out.println("All parts of frame " + frameId + " received");
            collector.emit(PROCESSED_FRAME_STREAM, tuple, new Values(frameId, foundRectAccount.get(frameId)));
            frameAccount.remove(frameId);
            foundRectAccount.remove(frameId);
        }
        collector.ack(tuple);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        outputFieldsDeclarer.declareStream(PROCESSED_FRAME_STREAM, new Fields(FIELD_FRAME_ID, FIELD_DETECTED_RECT));
    }
}
