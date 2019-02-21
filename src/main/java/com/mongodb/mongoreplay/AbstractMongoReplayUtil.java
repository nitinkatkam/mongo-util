package com.mongodb.mongoreplay;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.lang.StringUtils;
import org.bson.BSONDecoder;
import org.bson.BSONObject;
import org.bson.BasicBSONDecoder;
import org.bson.BasicBSONEncoder;
import org.bson.BsonBinaryReader;
import org.bson.ByteBufNIO;
import org.bson.Document;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodec;
import org.bson.io.ByteBufferBsonInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.Mongo;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.ReadPreference;
import com.mongodb.connection.ClusterDescription;
import com.mongodb.connection.ClusterType;
import com.mongodb.util.CallerBlocksPolicy;

public abstract class AbstractMongoReplayUtil {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractMongoReplayUtil.class);

    private final BasicBSONEncoder encoder;

    protected String[] fileNames;
    protected String[] removeUpdateFields;

    private int threads = 8;
    private int queueSize = 1000000;
    
    private final static int ONE_MINUTE = 60 * 1000;

    private Monitor monitor;

    private ThreadPoolExecutor pool = null;
    private BlockingQueue<Runnable> workQueue;
    List<Future<ReplayResult>> futures = new LinkedList<Future<ReplayResult>>();

    private String mongoUriStr;
    private MongoClient mongoClient;
    ClusterType clusterType;
    
    private ReadPreference readPreference;


    private int limit = Integer.MAX_VALUE;
    int count = 0;
    int written = 0;
    int ignored = 0;
    int getMoreCount = 0;

    public AbstractMongoReplayUtil() {
        this.encoder = new BasicBSONEncoder();
    }

    public void init() throws NoSuchMethodException, SecurityException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        logger.debug("mongoUriStr: " + mongoUriStr);
        MongoClientURI connectionString = new MongoClientURI(mongoUriStr);
        mongoClient = new MongoClient(connectionString);
        readPreference = mongoClient.getMongoClientOptions().getReadPreference();
        int seedListSize = mongoClient.getAllAddress().size();
        if (seedListSize == 1) {
            logger.warn("Only 1 host specified in seedlist");
        }
        mongoClient.getDatabase("admin").runCommand(new Document("ismaster", 1));
        
        
        Method method = Mongo.class.getDeclaredMethod("getClusterDescription");
        method.setAccessible(true);
        ClusterDescription cd = (ClusterDescription)method.invoke(mongoClient);
        this.clusterType = cd.getType();
        logger.debug("Connected: " + readPreference + " " + clusterType);
        
        workQueue = new ArrayBlockingQueue<Runnable>(queueSize);
        pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, workQueue, new CallerBlocksPolicy(ONE_MINUTE*5));
        pool.prestartAllCoreThreads();

        monitor = new Monitor(Thread.currentThread());
        monitor.setPool(pool);
        monitor.start();
    }

    public void close() {
        pool.shutdown();

        while (!pool.isTerminated()) {
            Thread.yield();
            try {
                Thread.sleep(5000);
                logger.debug("Waiting for pool");
            } catch (InterruptedException e) {
                // reset interrupted status
                Thread.interrupted();
            }
        }

        try {
            pool.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // reset interrupted status
            Thread.interrupted();
            if (null != monitor && monitor.isAlive()) {
                logger.error("interrupted error", e);
            }
            // harmless - this means the monitor wants to exit
            // if anything went wrong, the monitor will log it
            logger.warn("interrupted while waiting for pool termination");
        }

        halt();
        mongoClient.close();
        logger.debug("close() complete");
    }

    private void halt() {
        if (null != pool) {
            pool.shutdownNow();
        }

        while (null != monitor && monitor.isAlive()) {
            try {
                monitor.halt();
                // wait for monitor to exit
                monitor.join();
            } catch (InterruptedException e) {
                // reset interrupted status and ignore
                Thread.interrupted();
            }
        }

        if (Thread.currentThread().isInterrupted()) {
            logger.debug("resetting thread status");
            Thread.interrupted();
        }
    }

    public void replayFile(String filename) throws FileNotFoundException, DataFormatException {

        File file = new File(filename);
        InputStream inputStream = new BufferedInputStream(new FileInputStream(file));

        BSONDecoder decoder = new BasicBSONDecoder();
        
        try {

            int i = 0;
            while (inputStream.available() > 0) {

                if (count >= limit) {
                    break;
                }

                BSONObject obj = decoder.readObject(inputStream);
                if (obj == null) {
                    break;
                }

                BSONObject raw = (BSONObject) obj.get("rawop");
                if (raw == null) {
                    continue;
                }
                byte[] bytes = (byte[]) raw.get("body");

                if (bytes.length == 0) {
                    continue;
                }

                BSONObject header = (BSONObject) raw.get("header");

                if (header != null) {
                    int opcode = (Integer) header.get("opcode");
                    ByteBufferBsonInput bsonInput = new ByteBufferBsonInput(new ByteBufNIO(ByteBuffer.wrap(bytes)));
                    BsonBinaryReader reader = new BsonBinaryReader(bsonInput);

                    int messageLength = bsonInput.readInt32();
                    int requestId = bsonInput.readInt32();
                    int responseTo = bsonInput.readInt32();
                    int headerOpcode = bsonInput.readInt32();

                    if (opcode == 2004) {

                        int flags = bsonInput.readInt32();
                        String collectionName = bsonInput.readCString();
                        if (collectionName.equals("admin.$cmd") || collectionName.equals("local.$cmd")) {
                            continue;
                        }
                        int nskip = bsonInput.readInt32();
                        int nreturn = bsonInput.readInt32();
                        
                        Document commandDoc = new DocumentCodec().decode(reader, DecoderContext.builder().build());
                        
                        String databaseName = StringUtils.substringBefore(collectionName, ".$cmd");
                        processCommand(commandDoc, databaseName);

                        written++;

                    } else if (opcode == 2010) {
                        int p1 = bsonInput.getPosition();
                        
                        String databaseName = bsonInput.readCString();
                        if (databaseName.equals("local") || databaseName.equals("admin")) {
                            continue;
                        }
                        int p2 = bsonInput.getPosition();
                        
                        String command = bsonInput.readCString();
                        //p1 = bsonInput.getPosition();
                        //int commandLen = p1 - p2;
                        
                        Document commandDoc = new DocumentCodec().decode(reader, DecoderContext.builder().build());
                        commandDoc.remove("shardVersion");
                        processCommand(commandDoc, databaseName);
                        //System.out.println(commandDoc);
                    } else if (opcode == 2011) {
                        ignored++;
                    }
                }

                count++;
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
            }
        }
        logger.debug(String.format("%s objects read, %s filtered objects written, %s ignored", count, written, ignored));
        logger.debug(String.format("%s getMore", getMoreCount));
    }
    
    private void processCommand(Document commandDoc, String databaseName) {
        //System.out.println(commandDoc);
        Command command = null;
        if (commandDoc.containsKey("$query")) {
            Document queryDoc = (Document)commandDoc.get("$query");
            commandDoc = queryDoc;
        } else if (commandDoc.containsKey("query")) {
            Document queryDoc = (Document)commandDoc.get("query");
            commandDoc = queryDoc;
        }
        
        if (commandDoc.containsKey("find")) {
            command = Command.FIND;
        }  else if (commandDoc.containsKey("insert")) {
            command = Command.INSERT;
        }  else if (commandDoc.containsKey("update")) {
            command = Command.UPDATE;
        }  else if (commandDoc.containsKey("getMore")) {
            command = Command.GETMORE;
            getMoreCount++;
            ignored++;
            return;
        }  else if (commandDoc.containsKey("aggregate")) {
            command = Command.AGGREGATE;
            List<Document> stages = (List<Document>)commandDoc.get("pipeline");
            if (stages != null) {
                for (Document stage : stages) {
                    if (stage.containsKey("$mergeCursors")) {
                        return;
                    }
                }
                
            }
            commandDoc.remove("fromRouter");
        } else {
            logger.warn("ignored command: " + commandDoc);
            ignored++;
            return;
        }

        futures.add(pool.submit(new ReplayTask(monitor, mongoClient, commandDoc, command, databaseName, readPreference)));
    }

    @SuppressWarnings("static-access")
    protected static CommandLine initializeAndParseCommandLineOptions(String[] args) {
        Options options = new Options();
        options.addOption(new Option("help", "print this message"));
        options.addOption(
                OptionBuilder.withArgName("input mongoreplay bson file(s)").hasArgs().withLongOpt("files").create("f"));

        options.addOption(OptionBuilder.withArgName("remove update fields").hasArgs().withLongOpt("removeUpdateFields")
                .create("u"));

        options.addOption(OptionBuilder.withArgName("limit # operations").hasArg().withLongOpt("limit").create("l"));

        options.addOption(
                OptionBuilder.withArgName("play back target mongo uri").hasArg().withLongOpt("host").isRequired().create("h"));

        options.addOption(OptionBuilder.withArgName("# threads").hasArgs().withLongOpt("threads").create("t"));
        
        CommandLineParser parser = new GnuParser();
        CommandLine line = null;
        try {
            line = parser.parse(options, args);
            if (line.hasOption("help")) {
                printHelpAndExit(options);
            }
        } catch (org.apache.commons.cli.ParseException e) {
            System.out.println(e.getMessage());
            printHelpAndExit(options);
        } catch (Exception e) {
            e.printStackTrace();
            printHelpAndExit(options);
        }

        String[] fileNames = line.getOptionValues("f");

        if (fileNames == null) {
            printHelpAndExit(options);
        }

        return line;
    }
    
    protected void parseArgs(String args[]) {
        CommandLine line = initializeAndParseCommandLineOptions(args);

        this.fileNames = line.getOptionValues("f");
        this.removeUpdateFields = line.getOptionValues("u");
        String limitStr = line.getOptionValue("l");

        String mongoUriStr = line.getOptionValue("h");
       
        setRemoveUpdateFields(removeUpdateFields);
        setMongoUriStr(mongoUriStr);

        String threadsStr = line.getOptionValue("t");
        if (threadsStr != null) {
            int threads = Integer.parseInt(threadsStr);
            setThreads(threads);
        }

        if (limitStr != null) {
            int limit = Integer.parseInt(limitStr);
            setLimit(limit);
        }
    }

    private static void printHelpAndExit(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("replayUtil", options);
        System.exit(-1);
    }

    protected void setThreads(int threads) {
        this.threads = threads;
    }

    public String[] getRemoveUpdateFields() {
        return removeUpdateFields;
    }

    public void setRemoveUpdateFields(String[] removeUpdateFields) {
        this.removeUpdateFields = removeUpdateFields;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public void setMongoUriStr(String mongoUriStr) {
        this.mongoUriStr = mongoUriStr;
    }

}
