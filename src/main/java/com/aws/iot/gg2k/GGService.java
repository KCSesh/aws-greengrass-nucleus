/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.gg2k;

import com.aws.iot.config.*;
import com.aws.iot.config.Node;
import com.aws.iot.config.Topic;
import com.aws.iot.config.Topics;
import com.aws.iot.dependency.*;
import com.aws.iot.util.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;
import javax.inject.*;


public class GGService extends Lifecycle {
    public final Topics config;
    protected final CopyOnWriteArrayList<Lifecycle> explicitDependencies = new CopyOnWriteArrayList<>();
    public GGService(Topics c) {
        config = c;
    }
    @Override public String getName() {
        return config.getFullName();
    }
    @Override public void postInject() {
        super.postInject();
        Node d = config.getChild("dependencies");
        if (d == null)
            d = config.getChild("dependency");
        if (d == null)
            d = config.getChild("requires");
        //            System.out.println("requires: " + d);
        if(d == null){
            //TODO: handle defaultimpl without creating GGService for parent
            d =  config.getChild("defaultimpl");
        }
        if (d instanceof Topics)
            d = pickByOS((Topics) d);
        if (d instanceof Topic) {
            String ds = ((Topic) d).getOnce().toString();
            Matcher m = depParse.matcher(ds);
            while(m.find())
                addDependency(m.group(1), m.group(3));
            if (!m.hitEnd())
                errored("bad dependency syntax", ds);
        }
    }
    public void addDependency(String name, String startWhen) {
        if (startWhen == null)
            startWhen = State.Running.toString();
        State x = null;
        if (startWhen != null) {
            int len = startWhen.length();
            if (len > 0) {
                // do "friendly" match
                for (State s : State.values())
                    if (startWhen.regionMatches(true, 0, s.name(), 0, len)) {
                        x = s;
                        break;
                    }
                if (x == null)
                    errored("does not match any lifecycle state", startWhen);
            }
        }
        addDependency(name, x == null ? State.Running : x);
    }
    public void addDependency(String name, State startWhen) {
        try {
            Lifecycle d = locate(context, name);
            if (d != null) {
                explicitDependencies.add(d);
                addDependency(d, startWhen);
            }
            else
                errored("Couldn't locate", name);
        } catch (Throwable ex) {
            errored("Failure adding dependency", ex);
            ex.printStackTrace(System.out);
        }
    }
    private static final Pattern depParse = Pattern.compile(" *([^,:; ]+)(:([^,; ]+))?[,; ]*");
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        try {
            if (config == null)
                sb.append("[nameless]");
            else
                config.appendNameTo(sb);
            if (getState() != State.Running)
                sb.append(':').append(getState().toString());
        } catch (IOException ex) {
            sb.append(ex.toString());
        }
        return sb.toString();
    }
    public static Lifecycle locate(Context context, String name) throws Throwable {
        return context.getv(Lifecycle.class, name).computeIfEmpty(v->{
            Configuration c = context.get(Configuration.class);
            Topics t = c.lookupTopics(Configuration.splitPath(name));
            assert(t!=null);
            Lifecycle ret;
            Class clazz = null;
            Node n = t.getChild("class");
            if (n != null) {
                String cn = Coerce.toString(n);
                try {
                    clazz = Class.forName(cn);
                } catch (Throwable ex) {
                    ex.printStackTrace(System.out);
                    return errNode(context, name, "creating code-backed service from " + cn, ex);
                }
            }
            if(clazz==null) {
                Map<String,Class> si = context.getIfExists(Map.class, "service-implementors");
                if(si!=null) clazz = si.get(name);
            }
            if(clazz!=null) {
                try {
                    Constructor ctor = clazz.getConstructor(Topics.class);
                    ret = (GGService) ctor.newInstance(t);
                    if(clazz.getAnnotation(Singleton.class) !=null) {
                        context.put(ret.getClass(), v);
                    }
                } catch (Throwable ex) {
                    ex.printStackTrace(System.out);
                    ret = errNode(context, name, "creating code-backed service from " + clazz.getSimpleName(), ex);
                }
            }
            else if(t.isEmpty())
                ret = errNode(context, name, "No matching definition in system model", null);
            else
                try {
                    ret = new GenericExternalService(t);
                } catch (Throwable ex) {
                    ret = errNode(context, name, "Creating generic service", ex);
                }
            return ret;
        });
    }
    public static GGService errNode(Context context, String name, String message, Throwable ex) {
        try {
            context.get(Log.class).error("Error locating service",name,message,ex);
            GGService ggs = new GenericExternalService(Topics.errorNode(name,
                    "Error locating service " + name + ": " + message
                            + (ex == null ? "" : "\n\t" + ex)));
            return ggs;
        } catch (Throwable ex1) {
            context.get(Log.class).error(name,message,ex);
            return null;
        }
    }
    boolean shouldSkip(Topics n) {
        Node skipif = n.getChild("skipif");
        boolean neg = skipif == null && (skipif = n.getChild("doif")) != null;
        if (skipif instanceof Topic) {
            String expr = String.valueOf(((Topic) skipif).getOnce()).trim();
            if (expr.startsWith("!")) {
                expr = expr.substring(1).trim();
                neg = !neg;
            }
            Matcher m = skipcmd.matcher(expr);
            if (m.matches())
                switch (m.group(1)) {
                    case "onpath":
                        return Exec.which(m.group(2)) != null ^ neg; // XOR ?!?!
                    case "exists":
                        return Files.exists(Paths.get(context.get(GG2K.class).deTilde(m.group(2)))) ^ neg;
                    case "true": return !neg;
                    default:
                        errored("Unknown operator", m.group(1));
                        return false;
                }
            // Assume it's a shell script: test for 0 return code and nothing on stderr
            return neg ^ Exec.successful(expr);
        }
        return false;
    }
    private static final Pattern skipcmd = Pattern.compile("(exists|onpath) +(.+)");
    Node pickByOS(String name) {
        Node n = config.getChild(name);
        if (n instanceof Topics)
            n = pickByOS((Topics) n);
        return n;
    }
    private static final HashMap<String, Integer> ranks = new HashMap<>();
    public static int rank(String s) {
        Integer i = ranks.get(s);
        return i == null ? -1 : i;
    }
    static {
        // figure out what OS we're running and add applicable tags
        // The more specific a tag is, the higher its rank should be
        // TODO: a loopy set of hacks
        ranks.put("all", 0);
        ranks.put("any", 0);
        if (Files.exists(Paths.get("/bin/bash")))
            ranks.put("posix", 3);
        if (Files.exists(Paths.get("/usr/bin/bash")))
            ranks.put("posix", 3);
        if (Files.exists(Paths.get("/proc")))
            ranks.put("linux", 10);
        if (Files.exists(Paths.get("/usr/bin/apt-get")))
            ranks.put("debian", 11);
        if (Exec.isWindows)
            ranks.put("windows", 5);
        if (Files.exists(Paths.get("/usr/bin/yum")))
            ranks.put("fedora", 11);
        String sysver = Exec.sh("uname -a").toLowerCase();
        if (sysver.contains("ubuntu"))
            ranks.put("ubuntu", 20);
        if (sysver.contains("darwin"))
            ranks.put("macos", 20);
        if (sysver.contains("raspbian"))
            ranks.put("raspbian", 22);
        if (sysver.contains("qnx"))
            ranks.put("qnx", 22);
        if (sysver.contains("cygwin"))
            ranks.put("cygwin", 22);
        if (sysver.contains("freebsd"))
            ranks.put("freebsd", 22);
        if (sysver.contains("solaris") || sysver.contains("sunos"))
            ranks.put("solaris", 22);
        try {
            ranks.put(InetAddress.getLocalHost().getHostName(), 99);
        } catch (UnknownHostException ex) {
        }
    }
    Node pickByOS(Topics n) {
        Node bestn = null;
        int bestrank = -1;
        for (Map.Entry<String, Node> me : ((Topics) n).children.entrySet()) {
            int g = rank(me.getKey());
            if (g > bestrank) {
                bestrank = g;
                bestn = me.getValue();
            }
        }
        return bestn;
    }
    Periodicity timer;
    @Override
    public void startup() {
        timer = Periodicity.of(this);
        if(!errored()) setState(timer==null  // Let timer do the transition to Running==null
                ? State.Running
                : State.Finished);
    }
    @Override public void shutdown() {
        Periodicity t = timer;
        if(t!=null) t.shutdown();
    }
    public enum RunStatus { OK, NothingDone, Errored }
    protected RunStatus run(String name, IntConsumer background) {
        Node n = pickByOS(name);
        if(n==null) {
//            if(required) log().warn("Missing",name,this);
            return RunStatus.NothingDone;
        }
        return run(n, background);
    }
    protected RunStatus run(Node n, IntConsumer background) {
        return n instanceof Topic ? run((Topic) n, background)
             : n instanceof Topics ? run((Topics) n, background)
                : RunStatus.Errored;
    }
    @Inject ShellRunner shellRunner;
    protected RunStatus run(Topic t, IntConsumer background) {
        String cmd = Coerce.toString(t.getOnce());
        setStatus(cmd);
        IntConsumer nb = background!=null
                ? n->{
//                    setStatus(null);
                    background.accept(n);
                } : null;
        if(background==null) setStatus(null);
        RunStatus OK = shellRunner.run(t.getFullName(), cmd, nb, this) != ShellRunner.Failed
                ? RunStatus.OK : RunStatus.Errored;
        return OK;
    }
    protected RunStatus run(Topics t, IntConsumer background) {
        if (!shouldSkip(t)) {
            Node script = t.getChild("script");
            if (script instanceof Topic)
                return run((Topic) script, background);
            else {
                errored("Missing script: for ", t.getFullName());
                return RunStatus.Errored;
            }
        }
        else {
            log().significant("Skipping", t.getFullName());
            return RunStatus.OK;
        }
    }
    protected void addDependencies(HashSet<Lifecycle> deps) {
        deps.add(this);
        if (dependencies != null)
            dependencies.keySet().forEach(d -> {
                if (!deps.contains(d) && d instanceof GGService)
                    ((GGService)d).addDependencies(deps);
            });
    }
    @Override public boolean satisfiedBy(HashSet<Lifecycle> ready) {
        return dependencies == null
                || dependencies.keySet().stream().allMatch(l -> ready.contains(l));
    }

}