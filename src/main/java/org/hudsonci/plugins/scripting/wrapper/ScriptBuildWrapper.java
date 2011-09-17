/**
 * The MIT License
 *
 * Copyright (c) 2010-2011 Sonatype, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.hudsonci.plugins.scripting.wrapper;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.hudsonci.utils.common.Varargs.$;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Run.RunnerAbortedException;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import javax.enterprise.inject.Typed;
import javax.inject.Named;
import javax.inject.Singleton;

import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.hudsonci.utils.plugin.ui.JellyAccessible;
import org.hudsonci.utils.plugin.ui.RenderableEnum;
import org.kohsuke.stapler.DataBoundConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamOmitField;

/**
 * Script {@link BuildWrapper}.
 *
 * @author <a href="mailto:jason@planet57.com">Jason Dillon</a>
 */
@XStreamAlias("script-build-wrapper")
public class ScriptBuildWrapper
    extends BuildWrapper
{
    private static final Logger log = LoggerFactory.getLogger(ScriptBuildWrapper.class);

    private final String source;

    private final Mode mode;

    @XStreamOmitField
    private Class<? extends Script> compiledScriptType;

    @DataBoundConstructor
    public ScriptBuildWrapper(final String source, final String mode) {
        this.source = checkNotNull(source);
        this.mode = Mode.valueOf(checkNotNull(mode));
        compile();
    }

    @JellyAccessible
    public String getSource() {
        return source;
    }

    @JellyAccessible
    public Mode getMode() {
        return mode;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    private Object readResolve() {
        compile();
        return this;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (compiledScriptType != null) {
                InvokerHelper.removeClass(compiledScriptType);
            }
        }
        finally {
            super.finalize();
        }
    }

    private void compile() {
        if (compiledScriptType != null) {
            InvokerHelper.removeClass(compiledScriptType);
        }
        compiledScriptType = compile(source);
    }

    private Class<? extends Script> compile(final String source) {
        assert source != null;

        CompilerConfiguration cc = new CompilerConfiguration();
        // cc.setTargetDirectory(); TODO: Use tmpdir?

        Binding binding = new Binding();
        ClassLoader cl = Hudson.getInstance().pluginManager.uberClassLoader; // FIXME: ICK
        GroovyShell shell = new GroovyShell(cl, binding, cc);
        String scriptName = String.format("%s_%d.groovy", getClass().getSimpleName(), System.identityHashCode(this));

        log.trace("Compiling script {}:\n{}", scriptName, source);
        Script script = shell.parse(source, scriptName);
        Class<? extends Script> type = script.getClass();
        log.trace("Compiled script class: {}", type);

        return type;
    }

    private boolean matches(final Mode mode) {
        return this.mode == mode || this.mode == Mode.ALL;
    }

    private <T> T execute(final Mode mode, final Map<String, Object> vars, final Class<T> resultType) {
        log.debug("Executing script in mode: {}", mode);
        Object result;

        try {
            Script script = compiledScriptType.newInstance();
            Binding binding = new Binding(vars);
            binding.setVariable("mode", mode);
            binding.setVariable("container", this);

            if (log.isDebugEnabled()) {
                log.debug("Binding variables:");
                for (Object key : binding.getVariables().keySet()) {
                    Object value = binding.getVariables().get(key);
                    Class type = value != null ? value.getClass() : null;
                    log.debug("  {}={} ({})", $(key, value, type));
                }
            }

            script.setBinding(binding);
            result = script.run();
            log.debug("Script result: {}", result);
        }
        catch (Exception e) {
            log.error("Script execution failed", e);
            throw new RuntimeException("Script execution failed", e);
        }

        if (result != null) {
            Class type = result.getClass();
            if (!resultType.isAssignableFrom(type)) {
                log.warn("Incompatible result type; expect: {}, have: {}", resultType, type);
            }
        }

        //noinspection unchecked
        return (T)result;
    }

    @Override
    public Environment setUp(final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException, InterruptedException {
        log.trace("setUp");

        if (matches(Mode.SETUP)) {
            Map<String,Object> vars = new HashMap<String,Object>();
            vars.put("build", build);
            vars.put("launcher", launcher);
            vars.put("listener", listener);
            Environment result = execute(Mode.SETUP, vars, Environment.class);
            if (result != null) {
                return result;
            }
        }

        return new Environment()
        {
            @Override
            public boolean tearDown(final AbstractBuild build, final BuildListener listener) throws IOException, InterruptedException {
                log.trace("tearDown");

                if (matches(Mode.TEAR_DOWN)) {
                    Map<String,Object> vars = new HashMap<String,Object>();
                    vars.put("build", build);
                    vars.put("launcher", launcher);
                    Boolean result = execute(Mode.TEAR_DOWN, vars, Boolean.class);
                    if (result != null) {
                        return result;
                    }
                }

                return true;
            }

            @Override
            public void buildEnvVars(final Map<String, String> env) {
                log.trace("buildEnvVars");

                if (matches(Mode.ENVIRONMENT_VARIABLES)) {
                    Map<String,Object> vars = new HashMap<String,Object>();
                    vars.put("build", build);
                    vars.put("vars", env);
                    execute(Mode.ENVIRONMENT_VARIABLES, vars, null);
                }
            }
        };
    }

    @Override
    public Launcher decorateLauncher(final AbstractBuild build, final Launcher launcher, final BuildListener listener)
        throws IOException, InterruptedException, RunnerAbortedException
    {
        log.trace("decorateLauncher");

        if (matches(Mode.DECORATE_LAUNCHER)) {
            Map<String,Object> vars = new HashMap<String,Object>();
            vars.put("build", build);
            vars.put("launcher", launcher);
            vars.put("listener", listener);
            Launcher result = execute(Mode.DECORATE_LAUNCHER, vars, Launcher.class);
            if (result != null) {
                return result;
            }
        }

        return launcher;
    }

    @Override
    public OutputStream decorateLogger(final AbstractBuild build, final OutputStream logger) throws IOException, InterruptedException, RunnerAbortedException {
        log.trace("decorateLogger");

        if (matches(Mode.DECORATE_LOGGER)) {
            Map<String,Object> vars = new HashMap<String,Object>();
            vars.put("build", build);
            vars.put("logger", logger);
            OutputStream result = execute(Mode.DECORATE_LOGGER, vars, OutputStream.class);
            if (result != null) {
                return result;
            }
        }

        return logger;
    }

    @Override
    public void makeBuildVariables(final AbstractBuild build, final Map<String, String> variables) {
        log.trace("makeBuildVariables");

        if (matches(Mode.BUILD_VARIABLES)) {
            Map<String,Object> vars = new HashMap<String,Object>();
            vars.put("build", build);
            vars.put("vars", variables);
            execute(Mode.BUILD_VARIABLES, vars, null);
        }
    }

    @Named
    @Singleton
    @Typed(Descriptor.class)
    public static class DescriptorImpl
        extends BuildWrapperDescriptor
    {
        @Override
        public boolean isApplicable(final AbstractProject<?, ?> item) {
            return true;
        }

        @JellyAccessible
        public RenderableEnum[] getModeValues() {
            return RenderableEnum.forEnum(Mode.class);
        }

        @JellyAccessible
        public Mode getDefaultMode() {
            return Mode.SETUP;
        }

        @JellyAccessible
        public boolean isSelected(final Object value, final Object configValue, final Object defaultValue) {
            assert value != null;
            return value.equals(configValue) || (configValue == null && value.equals(defaultValue));
        }

        @Override
        public String getDisplayName() {
            return "Script Build Wrapper";
        }
    }

    public static enum Mode
    {
        SETUP,
        TEAR_DOWN,
        DECORATE_LAUNCHER,
        DECORATE_LOGGER,
        BUILD_VARIABLES,
        ENVIRONMENT_VARIABLES,
        ALL
    }
}