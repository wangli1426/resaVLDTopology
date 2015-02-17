package generateTraj;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import topology.Serializable;

import java.nio.FloatBuffer;
import java.util.*;

import static org.bytedeco.javacpp.opencv_core.*;
import static tool.Constant.*;

/**
 * Created by Tom Fu
 * Input is raw video frames, output optical flow results between every two consecutive frames.
 * Maybe use global grouping and only one task/executor
 * Similar to frame producer, maintain an ordered list of frames
 */
///TODO: maybe move the mbhInfo generation to another bolt, which also needs opticalFlow data.
public class optFlowTrackerDelta extends BaseRichBolt {
    OutputCollector collector;

    private HashMap<Integer, Serializable.Mat> optFlowMap;
    private HashMap<Integer, Queue<TraceMetaAndLastPoint>> traceQueue;
    DescInfo mbhInfo;

    static int patch_size = 32;
    static int nxy_cell = 2;
    static int nt_cell = 3;
    static float min_flow = 0.4f * 0.4f;

    String traceAggBoltNameString;
    List<Integer> traceAggBoltTasks;

    public optFlowTrackerDelta(String traceAggBoltNameString) {
        this.traceAggBoltNameString = traceAggBoltNameString;
    }

    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        this.collector = outputCollector;
        this.mbhInfo = new DescInfo(8, 0, 1, patch_size, nxy_cell, nt_cell, min_flow);
        optFlowMap = new HashMap<>();
        traceQueue = new HashMap<>();

        this.traceAggBoltTasks = topologyContext.getComponentTasks(traceAggBoltNameString);
    }

    @Override
    public void execute(Tuple tuple) {

        String streamId = tuple.getSourceStreamId();
        int frameId = tuple.getIntegerByField(FIELD_FRAME_ID);

        if (streamId.equals(STREAM_RENEW_TRACE) || streamId.equals(STREAM_NEW_TRACE)) {
            TraceMetaAndLastPoint trace = (TraceMetaAndLastPoint) tuple.getValueByField(FIELD_TRACE_META_LAST_POINT);
            traceQueue.computeIfAbsent(frameId, k -> new LinkedList<>()).add(trace);
            if (optFlowMap.containsKey(frameId)) {
                processTraceRecords(frameId);
            }

        } else if (streamId.equals(STREAM_OPT_FLOW)) {
            IplImage fake = new IplImage();
            Serializable.Mat sMat = (Serializable.Mat) tuple.getValueByField(FIELD_FRAME_MAT);
            optFlowMap.computeIfAbsent(frameId, k->sMat);
            if (traceQueue.containsKey(frameId)) {
                processTraceRecords(frameId);
            }

        } else if (streamId.equals(STREAM_CACHE_CLEAN)) {
            optFlowMap.remove(frameId);
            traceQueue.remove(frameId);
            System.out.println("clean_cache of frame: " + frameId);
        }

        collector.ack(tuple);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        //outputFieldsDeclarer.declareStream(STREAM_EXIST_TRACE, new Fields(FIELD_FRAME_ID, FIELD_TRACE_CONTENT));
        //outputFieldsDeclarer.declareStream(STREAM_REMOVE_TRACE, new Fields(FIELD_FRAME_ID, FIELD_TRACE_CONTENT));
        outputFieldsDeclarer.declareStream(STREAM_EXIST_TRACE, true, new Fields(FIELD_FRAME_ID, FIELD_TRACE_CONTENT, FIELD_TRACE_ID));
        outputFieldsDeclarer.declareStream(STREAM_REMOVE_TRACE, true, new Fields(FIELD_FRAME_ID, FIELD_TRACE_CONTENT, FIELD_TRACE_ID));
    }

    public void processTraceRecords(int frameId) {
        IplImage imageFK = new IplImage();
        Serializable.Mat sMat = optFlowMap.get(frameId);
        Mat orgMat = sMat.toJavaCVMat();
        IplImage flow = orgMat.asIplImage();
        Queue<TraceMetaAndLastPoint> traceRecords = traceQueue.get(frameId);
        while (!traceRecords.isEmpty()) {
            TraceMetaAndLastPoint trace = traceRecords.poll();
            Serializable.CvPoint2D32f pointOut = getNextFlowPoint(flow, trace.lastPoint);
            int tID = trace.getTargetTaskID(this.traceAggBoltTasks);

            if (pointOut != null) {
                TraceMetaAndLastPoint traceNext = new TraceMetaAndLastPoint(trace.traceID, pointOut);
                collector.emitDirect(tID, STREAM_EXIST_TRACE, new Values(frameId, traceNext, trace.traceID));
            } else {
                collector.emitDirect(tID, STREAM_REMOVE_TRACE, new Values(frameId, trace.traceID, trace.traceID));
            }
        }
    }

    public Serializable.CvPoint2D32f getNextFlowPoint(IplImage flow, Serializable.CvPoint2D32f point_in) {

        int width = flow.width();
        int height = flow.height();

        int x = cvFloor(point_in.x());
        int y = cvFloor(point_in.y());

        LinkedList<Float> xs = new LinkedList<>();
        LinkedList<Float> ys = new LinkedList<>();
        for (int m = x - 1; m <= x + 1; m++) {
            for (int n = y - 1; n <= y + 1; n++) {
                int p = Math.min(Math.max(m, 0), width - 1);
                int q = Math.min(Math.max(n, 0), height - 1);

                FloatBuffer floatBuffer = flow.getByteBuffer(q * flow.widthStep()).asFloatBuffer();
                int xsIndex = 2 * p;
                int ysIndex = 2 * p + 1;

                xs.addLast(floatBuffer.get(xsIndex));
                ys.addLast(floatBuffer.get(ysIndex));
            }
        }
        xs.sort(Float::compare);
        ys.sort(Float::compare);

        //TODO: need to optimize
        int size = xs.size() / 2;
        for (int m = 0; m < size; m++) {
            xs.removeLast();
            ys.removeLast();
        }

        Serializable.CvPoint2D32f offset = new Serializable.CvPoint2D32f();
        offset.x(xs.getLast());
        offset.y(ys.getLast());

        Serializable.CvPoint2D32f point_out = new Serializable.CvPoint2D32f();
        point_out.x(point_in.x() + offset.x());
        point_out.y(point_in.y() + offset.y());

        if (point_out.x() > 0 && point_out.x() < width && point_out.y() > 0 && point_out.y() < height) {
            return point_out;
        } else {
            return null;
        }
    }
}