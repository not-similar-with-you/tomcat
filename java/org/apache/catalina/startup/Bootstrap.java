/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.startup;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;


/**
 * Bootstrap loader for Catalina.  This application constructs a class loader
 * for use in loading the Catalina internal classes (by accumulating all of the
 * JAR files found in the "server" directory under "catalina.home"), and
 * starts the regular execution of the container.  The purpose of this
 * roundabout approach is to keep the Catalina internal classes (and any
 * other classes they depend on, such as an XML parser) out of the system
 * class path and therefore not visible to application level classes.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public final class Bootstrap {

    private static final Log log = LogFactory.getLog(Bootstrap.class);

    /**
     * Daemon object used by main.
     */
    private static Bootstrap daemon = null;

    private static final File catalinaBaseFile;
    private static final File catalinaHomeFile;

    private static final Pattern PATH_PATTERN = Pattern.compile("(\".*?\")|(([^,])*)");

    static {
        // Will always be non-null 项目路径
        String userDir = System.getProperty("user.dir");

        // Home first
        String home = System.getProperty(Globals.CATALINA_HOME_PROP);
        File homeFile = null;

        if (home != null) {
            File f = new File(home);
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        if (homeFile == null) {
            // First fall-back. See if current directory is a bin directory
            // in a normal Tomcat install 初始文件不存在
            File bootstrapJar = new File(userDir, "bootstrap.jar");

            if (bootstrapJar.exists()) {
                File f = new File(userDir, "..");
                try {
                    homeFile = f.getCanonicalFile();
                } catch (IOException ioe) {
                    homeFile = f.getAbsoluteFile();
                }
            }
        }

        if (homeFile == null) {
            // Second fall-back. Use current directory
            File f = new File(userDir);
            try {
                homeFile = f.getCanonicalFile(); //抽象路径名的规范路径名字符串
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        catalinaHomeFile = homeFile;
        System.setProperty(
                Globals.CATALINA_HOME_PROP, catalinaHomeFile.getPath());// 设置 catalina.home

        // Then base
        String base = System.getProperty(Globals.CATALINA_BASE_PROP);
        if (base == null) { // catalina.base = catalina.home
            catalinaBaseFile = catalinaHomeFile;
        } else {
            File baseFile = new File(base);
            try {
                baseFile = baseFile.getCanonicalFile();
            } catch (IOException ioe) {
                baseFile = baseFile.getAbsoluteFile();
            }
            catalinaBaseFile = baseFile;
        }
        System.setProperty(
                Globals.CATALINA_BASE_PROP, catalinaBaseFile.getPath());
    }

    // -------------------------------------------------------------- Variables


    /**
     * Daemon reference.
     */
    private Object catalinaDaemon = null;


    ClassLoader commonLoader = null;
    ClassLoader catalinaLoader = null;
    ClassLoader sharedLoader = null;


    // -------------------------------------------------------- Private Methods


    private void initClassLoaders() {
        try {
            commonLoader = createClassLoader("common", null);
            if( commonLoader == null ) {
                // no config file, default to this loader - we might be in a 'single' env.
                commonLoader=this.getClass().getClassLoader();
            }
            // server.loader (catalinaLoader)， share.loader未配置 使用 commonLoader 加载器
            catalinaLoader = createClassLoader("server", commonLoader);
            sharedLoader = createClassLoader("shared", commonLoader);
        } catch (Throwable t) {
            handleThrowable(t);
            log.error("Class loader creation threw exception", t);
            System.exit(1);
        }
    }


    private ClassLoader createClassLoader(String name, ClassLoader parent)
        throws Exception {
        // name.loader  值
        String value = CatalinaProperties.getProperty(name + ".loader");
        if ((value == null) || (value.equals("")))// 属性文件 中为空 直接返回
            return parent;

        value = replace(value);

        List<Repository> repositories = new ArrayList<>();

        String[] repositoryPaths = getPaths(value);

        for (String repository : repositoryPaths) {
            // Check for a JAR URL repository
            try {
                @SuppressWarnings("unused")
                URL url = new URL(repository);
                repositories.add(
                        new Repository(repository, RepositoryType.URL));
                continue;
            } catch (MalformedURLException e) {
                // Ignore
            }

            // Local repository
            if (repository.endsWith("*.jar")) {
                repository = repository.substring //F:\ideaSpace\tomcat/lib/*.jar 去除 *.jar 字符串路径
                    (0, repository.length() - "*.jar".length());
                repositories.add(
                        new Repository(repository, RepositoryType.GLOB));
            } else if (repository.endsWith(".jar")) {
                repositories.add(
                        new Repository(repository, RepositoryType.JAR));
            } else {//增加为 文件夹 路径
                repositories.add(
                        new Repository(repository, RepositoryType.DIR));
            }
        }
        //返回 urlClassLoader 加载器
        return ClassLoaderFactory.createClassLoader(repositories, parent);
    }


    /**
     * System property replacement in the given string.
     *
     * @param str The original string
     * @return the modified string
     */
    protected String replace(String str) {// 替换 ${ } 中的内容为 路径
        // Implementation is copied from ClassLoaderLogManager.replace(),
        // but added special processing for catalina.home and catalina.base.
        String result = str;
        int pos_start = str.indexOf("${");// ${ 的索引位置
        if (pos_start >= 0) {// 存在 ${ 时
            StringBuilder builder = new StringBuilder();
            int pos_end = -1;
            while (pos_start >= 0) {
                builder.append(str, pos_end + 1, pos_start);//"${catalina.base}/lib","${catalina.base}/lib/*.jar","${catalina.home}/lib","${catalina.home}/lib/*.jar" 第二次时截取前一个 } 与 ${ 之间的字符串 /lib","";
                pos_end = str.indexOf('}', pos_start + 2); // 从 ${ 之后开始 } 的位置
                if (pos_end < 0) {// 找不到 }
                    pos_end = pos_start - 1;
                    break;
                }
                String propName = str.substring(pos_start + 2, pos_end);// 截取 ${   } 之间的变量名称
                String replacement;
                if (propName.length() == 0) {
                    replacement = null;
                } else if (Globals.CATALINA_HOME_PROP.equals(propName)) { // catalina.home
                    replacement = getCatalinaHome();
                } else if (Globals.CATALINA_BASE_PROP.equals(propName)) {// catalina.base
                    replacement = getCatalinaBase();
                } else {
                    replacement = System.getProperty(propName);
                }
                if (replacement != null) {
                    builder.append(replacement);
                } else {
                    builder.append(str, pos_start, pos_end + 1);
                }
                pos_start = str.indexOf("${", pos_end + 1);// 下一个 ${ 的位置 找不到跳出循环
            }//拼接最后一部分字符串
            builder.append(str, pos_end + 1, str.length());
            result = builder.toString();
        }
        return result;
    }


    /**
     * Initialize daemon.
     * @throws Exception Fatal initialization error
     */
    public void init() throws Exception {

        initClassLoaders();

        Thread.currentThread().setContextClassLoader(catalinaLoader);

        SecurityClassLoad.securityClassLoad(catalinaLoader);

        // Load our startup class and call its process() method
        if (log.isDebugEnabled())
            log.debug("Loading startup class");
        // urlClassLoader loadClass 使用默认 classLoader 原 loadClass 方法 不进行 链接 ，初始化（执行static 块代码 参数--因编译成字节码 实际只执行 static 块代码）
        Class<?> startupClass =
            catalinaLoader.loadClass
            ("org.apache.catalina.startup.Catalina");//实际使用应用程序类加载器（双亲委派模型）
        Object startupInstance = startupClass.newInstance();// 实例化 Catalina 调用 Catalina 构造方法

        // Set the shared extensions class loader
        if (log.isDebugEnabled())
            log.debug("Setting startup class properties");
        String methodName = "setParentClassLoader";
        Class<?> paramTypes[] = new Class[1];
        //加载ClassLoader 类， 链接，初始化
        paramTypes[0] = Class.forName("java.lang.ClassLoader");
        Object paramValues[] = new Object[1];
        paramValues[0] = sharedLoader;
        // 获取 Catalina setParentClassLoader 方法对象
        Method method =
            startupInstance.getClass().getMethod(methodName, paramTypes);
        // 调用 setParentClassLoader 参数为 urlClassLoader
        method.invoke(startupInstance, paramValues);
        //指向 Catalina 类
        catalinaDaemon = startupInstance;

    }


    /**
     * Load daemon.
     */
    private void load(String[] arguments)
        throws Exception {

        // Call the load() method
        String methodName = "load";
        Object param[];
        Class<?> paramTypes[];
        if (arguments==null || arguments.length==0) {// 未输入命令行参数 下面调用 Catalina load 无参数的方法
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
            catalinaDaemon.getClass().getMethod(methodName, paramTypes);
        if (log.isDebugEnabled())
            log.debug("Calling startup class " + method);
        method.invoke(catalinaDaemon, param);

    }


    /**
     * getServer() for configtest
     */
    private Object getServer() throws Exception {

        String methodName = "getServer";
        Method method =
            catalinaDaemon.getClass().getMethod(methodName);
        return method.invoke(catalinaDaemon);

    }


    // ----------------------------------------------------------- Main Program


    /**
     * Load the Catalina daemon.
     * @param arguments Initialization arguments
     * @throws Exception Fatal initialization error
     */
    public void init(String[] arguments)
        throws Exception {

        init();
        load(arguments);

    }


    /**
     * Start the Catalina daemon.
     * @throws Exception Fatal start error
     */
    public void start()
        throws Exception {
        if( catalinaDaemon==null ) init();

        Method method = catalinaDaemon.getClass().getMethod("start", (Class [] )null);
        method.invoke(catalinaDaemon, (Object [])null);

    }


    /**
     * Stop the Catalina Daemon.
     * @throws Exception Fatal stop error
     */
    public void stop()
        throws Exception {

        Method method = catalinaDaemon.getClass().getMethod("stop", (Class [] ) null);
        method.invoke(catalinaDaemon, (Object [] ) null);

    }


    /**
     * Stop the standalone server.
     * @throws Exception Fatal stop error
     */
    public void stopServer()
        throws Exception {

        Method method =
            catalinaDaemon.getClass().getMethod("stopServer", (Class []) null);
        method.invoke(catalinaDaemon, (Object []) null);

    }


   /**
     * Stop the standalone server.
     * @param arguments Command line arguments
     * @throws Exception Fatal stop error
     */
    public void stopServer(String[] arguments)
        throws Exception {

        Object param[];
        Class<?> paramTypes[];
        if (arguments==null || arguments.length==0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
            catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
        method.invoke(catalinaDaemon, param);

    }


    /**
     * Set flag. 实际 调用 catalina 中的 setAwait 方法设置为 true
     * @param await <code>true</code> if the daemon should block
     * @throws Exception Reflection error
     */
    public void setAwait(boolean await)
        throws Exception {

        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Boolean.TYPE;
        Object paramValues[] = new Object[1];
        paramValues[0] = Boolean.valueOf(await);
        Method method = // catalina 类对象 中的 setAwait(boolean b) 方法，并调用方法
            catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
        method.invoke(catalinaDaemon, paramValues);

    }

    public boolean getAwait()
        throws Exception
    {
        Class<?> paramTypes[] = new Class[0];
        Object paramValues[] = new Object[0];
        Method method =
            catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
        Boolean b=(Boolean)method.invoke(catalinaDaemon, paramValues);
        return b.booleanValue();
    }


    /**
     * Destroy the Catalina Daemon.
     */
    public void destroy() {

        // FIXME

    }


    /**
     * Main method and entry point when starting Tomcat via the provided
     * scripts.
     *
     * @param args Command line arguments to be processed
     */
    public static void main(String args[]) {

        if (daemon == null) {
            // Don't set daemon until init() has completed
            Bootstrap bootstrap = new Bootstrap();
            try {
                bootstrap.init();
            } catch (Throwable t) {
                handleThrowable(t);
                t.printStackTrace();
                return;
            }
            daemon = bootstrap;
        } else {
            // When running as a service the call to stop will be on a new
            // thread so make sure the correct class loader is used to prevent
            // a range of class not found exceptions.
            Thread.currentThread().setContextClassLoader(daemon.catalinaLoader);
        }

        try {
            String command = "start";
            if (args.length > 0) {
                command = args[args.length - 1];
            }

            if (command.equals("startd")) {
                args[args.length - 1] = "start";
                daemon.load(args);
                daemon.start();
            } else if (command.equals("stopd")) {
                args[args.length - 1] = "stop";
                daemon.stop();
            } else if (command.equals("start")) {
                daemon.setAwait(true);
                daemon.load(args);
                daemon.start();
            } else if (command.equals("stop")) {
                daemon.stopServer(args);
            } else if (command.equals("configtest")) {
                daemon.load(args);
                if (null==daemon.getServer()) {
                    System.exit(1);
                }
                System.exit(0);
            } else {
                log.warn("Bootstrap: command \"" + command + "\" does not exist.");
            }
        } catch (Throwable t) {
            // Unwrap the Exception for clearer error reporting
            if (t instanceof InvocationTargetException &&
                    t.getCause() != null) {
                t = t.getCause();
            }
            handleThrowable(t);
            t.printStackTrace();
            System.exit(1);
        }

    }


    /**
     * Obtain the name of configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     * @return the catalina home
     */
    public static String getCatalinaHome() {
        return catalinaHomeFile.getPath();
    }


    /**
     * Obtain the name of the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHome()} will be used.
     * @return the catalina base
     */
    public static String getCatalinaBase() {
        return catalinaBaseFile.getPath();
    }


    /**
     * Obtain the configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     * @return the catalina home as a file
     */
    public static File getCatalinaHomeFile() {
        return catalinaHomeFile;
    }


    /**
     * Obtain the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHomeFile()} will be used.
     * @return the catalina base as a file
     */
    public static File getCatalinaBaseFile() {
        return catalinaBaseFile;
    }


    // Copied from ExceptionUtils since that class is not visible during start
    private static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        // All other instances of Throwable will be silently swallowed
    }


    // Protected for unit testing
    protected static String[] getPaths(String value) {

        List<String> result = new ArrayList<>();
        Matcher matcher = PATH_PATTERN.matcher(value);

        while (matcher.find()) {
            String path = value.substring(matcher.start(), matcher.end());

            path = path.trim();
            if (path.length() == 0) {
                continue;
            }

            char first = path.charAt(0);
            char last = path.charAt(path.length() - 1);
            // 字符串在引号中 长度 大于 0
            if (first == '"' && last == '"' && path.length() > 1) {
                path = path.substring(1, path.length() - 1);// 去除引号
                path = path.trim();
                if (path.length() == 0) {
                    continue;
                }
            } else if (path.contains("\"")) {
                // Unbalanced quotes
                // Too early to use standard i18n support. The class path hasn't
                // been configured.
                throw new IllegalArgumentException(
                        "The double quote [\"] character only be used to quote paths. It must " +
                        "not appear in a path. This loader path is not valid: [" + value + "]");
            } else {
                // Not quoted - NO-OP
            }

            result.add(path);
        }
        return result.toArray(new String[result.size()]);
    }
}
