package org.jenkinsci.plugins.buildnameupdater;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.buildnamesetter.Executor;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * This plugin replace the build name with the first line from a file on a slave.
 *
 * @author Lev Mishin
 */
public class BuildNameUpdater extends Builder {

    private final String buildName;
    private final String macroTemplate;
    private final boolean fromFile;
    private final boolean fromMacro;
    private final boolean macroFirst;

    private static final Logger LOGGER = Logger.getLogger(BuildNameUpdater.class.getName());

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public BuildNameUpdater(boolean fromFile, String buildName, boolean fromMacro, String macroTemplate, boolean macroFirst) {
        this.buildName = buildName;
        this.macroTemplate = macroTemplate;
        this.fromFile = fromFile;
        this.fromMacro = fromMacro;
        this.macroFirst = macroFirst;
    }

    @SuppressWarnings("unused")
    public boolean getFromFile() {
        return fromFile;
    }

    @SuppressWarnings("unused")
    public boolean getMacroFirst() {
        return macroFirst;
    }

    @SuppressWarnings("unused")
    public boolean getFromMacro() {
        return fromMacro;
    }

    @SuppressWarnings("unused")
    public String getBuildName() {
        return buildName;
    }

    @SuppressWarnings("unused")
    public String getMacroTemplate() {
        return macroTemplate;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        String buildNameToSet = "";

        if (fromFile) {
            buildNameToSet = readFromFile(build, listener, buildName);
        }

        if (fromMacro) {
            String evaluatedMacro = getFromMacro(build, listener, macroTemplate);

            listener.getLogger().println("Evaluated macro: '" + evaluatedMacro + "'");

            buildNameToSet = macroFirst ? evaluatedMacro + buildNameToSet : buildNameToSet + evaluatedMacro;
        }

        if (StringUtils.isNotBlank(buildNameToSet)) {
            Executor executor = new Executor(build, listener);
            executor.setName(buildNameToSet);
        }

        return true;
    }

    private String getFromMacro(AbstractBuild build, BuildListener listener, String macro) {
        String result = null;
        try {
            result = TokenMacro.expandAll(build, listener, macro);
        } catch (MacroEvaluationException e) {
            listener.getLogger().println("Failed to evaluate macro '" + macro + "'");
            LOGGER.log(Level.WARNING, "Failed to evaluate macro '" + macro + "': ", e);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Exception was thrown during macro evaluation: ", e);
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Macro evaluation was interrupted: ", e);
            listener.getLogger().println("Macro evaluating failed with:");
        }
        LOGGER.log(Level.INFO, "Macro evaluated: '" + result + "'");
        return result;
    }

    private String readFromFile(AbstractBuild build, BuildListener listener, String filePath) {
        String version = "";

        if (StringUtils.isBlank(filePath)) {
            listener.getLogger().println("File path is empty.");
            return "";
        }

        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            listener.getLogger().println("Workspace is empty.");
            return "";
        }
        FilePath fp = new FilePath(workspace, filePath);

        listener.getLogger().println("Getting version from file: " + fp);

        try {
            version = fp.act(new FileCallable());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read file: ", e);
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Getting name from file was interrupted: ", e);
        }

        listener.getLogger().println("Loaded version is " + version);
        return StringUtils.defaultString(version);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    private static class FileCallable extends MasterToSlaveFileCallable<String> {
        private static final long serialVersionUID = 1L;

        @Override
        public String invoke(File file, VirtualChannel channel) throws IOException {
            if (file.getAbsoluteFile().exists()) {
                LOGGER.log(Level.INFO, "File is found, reading...");
                try (BufferedReader br = new BufferedReader(
                        new FileReader(file.getAbsoluteFile(), StandardCharsets.UTF_8))) {
                    return br.readLine();
                }
            } else {
                LOGGER.log(Level.WARNING, "File was not found.");
                return "";
            }
        }
    }

    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        public DescriptorImpl() {
            load();
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckName(@QueryParameter String value) {
            if (value.isEmpty())
                return FormValidation.error("Please set a file path");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public String getDisplayName() {
            return "Update build name";
        }
    }
}
