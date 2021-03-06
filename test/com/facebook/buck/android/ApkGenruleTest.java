/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import static com.facebook.buck.util.BuckConstant.GEN_DIR;
import static com.facebook.buck.util.BuckConstant.GEN_PATH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.java.KeystoreBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.FakeBuildable;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.testutil.IdentityPathAbsolutifier;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Unit test for {@link com.facebook.buck.android.ApkGenrule}.
 */
public class ApkGenruleTest {

  private static final Function<Path, Path> relativeToAbsolutePathFunction =
      new Function<Path, Path>() {
        @Override
        public Path apply(Path path) {
          return Paths.get("/opt/local/fbandroid").resolve(path);
        }
      };

  private void createSampleAndroidBinaryRule(BuildRuleResolver ruleResolver) {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    BuildTarget libAndroidTarget = BuildTargetFactory.newInstance("//:lib-android");
    BuildRule androidLibRule = JavaLibraryBuilder.createBuilder(libAndroidTarget)
        .addSrc(Paths.get("java/com/facebook/util/Facebook.java"))
        .build(ruleResolver);

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    Keystore keystore = (Keystore) KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(Paths.get("keystore/debug.keystore"))
        .setProperties(Paths.get("keystore/debug.keystore.properties"))
        .build(ruleResolver)
        .getBuildable();

    AndroidBinaryBuilder.newBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:fb4a"))
        .setManifest(new TestSourcePath("AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(keystore)
        .setOriginalDeps(ImmutableSortedSet.of(androidLibRule))
        .build(ruleResolver);
  }

  @Test
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  public void testCreateAndRunApkGenrule() throws IOException, NoSuchBuildTargetException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    createSampleAndroidBinaryRule(ruleResolver);

    // From the Python object, create a ApkGenruleBuildRuleFactory to create a ApkGenrule.Builder
    // that builds a ApkGenrule from the Python object.
    BuildTargetParser parser = EasyMock.createNiceMock(BuildTargetParser.class);
    final BuildTarget apkTarget = BuildTargetFactory.newInstance("//:fb4a");
    EasyMock.expect(parser.parse(EasyMock.eq(":fb4a"), EasyMock.anyObject(ParseContext.class)))
        .andStubReturn(apkTarget);
    EasyMock.replay(parser);

    BuildTarget buildTarget = new BuildTarget("//src/com/facebook", "sign_fb4a");
    ApkGenruleDescription description = new ApkGenruleDescription();
    ApkGenruleDescription.Arg arg = description.createUnpopulatedConstructorArg();
    arg.apk = new FakeBuildRule(AndroidBinaryDescription.TYPE, apkTarget) {
      @Override
      public Buildable getBuildable() {
        class Dummy extends FakeBuildable implements InstallableApk {

          @Override
          public BuildTarget getBuildTarget() {
            return apkTarget;
          }

          @Override
          public Path getManifestPath() {
            return Paths.get("spoof");
          }

          @Override
          public Path getApkPath() {
            return Paths.get("buck-out/gen/fb4a.apk");
          }

          @Override
          public Optional<ExopackageInfo> getExopackageInfo() {
            return Optional.absent();
          }
        }

        return new Dummy();
      }
    };
    arg.bash = Optional.of("");
    arg.cmd = Optional.of("python signer.py $APK key.properties > $OUT");
    arg.cmdExe = Optional.of("");
    arg.deps = Optional.of(ImmutableSortedSet.<BuildRule>of());
    arg.out = "signed_fb4a.apk";
    arg.srcs = Optional.of(ImmutableList.of(
            Paths.get("src/com/facebook/signer.py"), Paths.get("src/com/facebook/key.properties")));
    FakeBuildRuleParams params = new FakeBuildRuleParams(buildTarget) {
      @Override
      public Function<Path, Path> getPathAbsolutifier() {
        return relativeToAbsolutePathFunction;
      }
    };
    ApkGenrule apkGenrule = description.createBuildable(params, arg);
    BuildRule rule = new AbstractBuildable.AnonymousBuildRule(
        ApkGenruleDescription.TYPE,
        apkGenrule,
        params);
    ruleResolver.addToIndex(buildTarget, rule);

    // Verify all of the observers of the Genrule.
    String expectedApkOutput =
        "/opt/local/fbandroid/" + GEN_DIR + "/src/com/facebook/sign_fb4a.apk";
    assertEquals(expectedApkOutput,
        apkGenrule.getAbsoluteOutputFilePath());
    BuildContext buildContext = BuildContext.builder()
        .setDependencyGraph(EasyMock.createMock(DependencyGraph.class))
        .setStepRunner(EasyMock.createNiceMock(StepRunner.class))
        .setProjectFilesystem(EasyMock.createNiceMock(ProjectFilesystem.class))
        .setArtifactCache(EasyMock.createMock(ArtifactCache.class))
        .setJavaPackageFinder(EasyMock.createNiceMock(JavaPackageFinder.class))
        .setEventBus(BuckEventBusFactory.newInstance())
        .build();
    ImmutableSortedSet<Path> inputsToCompareToOutputs = ImmutableSortedSet.of(
        Paths.get("src/com/facebook/key.properties"),
        Paths.get("src/com/facebook/signer.py"));
    assertEquals(inputsToCompareToOutputs,
        apkGenrule.getInputsToCompareToOutput());

    // Verify that the shell commands that the genrule produces are correct.
    List<Step> steps = apkGenrule.getBuildSteps(buildContext, new FakeBuildableContext());
    assertEquals(7, steps.size());

    Step firstStep = steps.get(0);
    assertTrue(firstStep instanceof RmStep);
    RmStep rmCommand = (RmStep) firstStep;
    ExecutionContext executionContext = newEmptyExecutionContext();
    assertEquals(
        "First command should delete the output file to be written by the genrule.",
        ImmutableList.of(
            "rm",
            "-f",
            apkGenrule.getPathToOutputFile().toString()),
        rmCommand.getShellCommand(executionContext));

    Step secondStep = steps.get(1);
    assertTrue(secondStep instanceof MkdirStep);
    MkdirStep mkdirCommand = (MkdirStep) secondStep;
    assertEquals(
        "Second command should make sure the output directory exists.",
        Paths.get(GEN_DIR + "/src/com/facebook/"),
        mkdirCommand.getPath(executionContext));

    Step thirdStep = steps.get(2);
    assertTrue(thirdStep instanceof MakeCleanDirectoryStep);
    MakeCleanDirectoryStep secondMkdirCommand = (MakeCleanDirectoryStep) thirdStep;
    Path relativePathToTmpDir = GEN_PATH.resolve("src/com/facebook/sign_fb4a__tmp");
    assertEquals(
        "Third command should make sure the temp directory exists.",
        relativePathToTmpDir,
        secondMkdirCommand.getPath());

    Step fourthStep = steps.get(3);
    assertTrue(fourthStep instanceof MakeCleanDirectoryStep);
    MakeCleanDirectoryStep thirdMkdirCommand = (MakeCleanDirectoryStep) fourthStep;
    Path relativePathToSrcDir = GEN_PATH.resolve("src/com/facebook/sign_fb4a__srcs");
    assertEquals(
        "Fourth command should make sure the temp directory exists.",
        relativePathToSrcDir,
        thirdMkdirCommand.getPath());

    MkdirAndSymlinkFileStep linkSource1 = (MkdirAndSymlinkFileStep) steps.get(4);
    assertEquals(Paths.get("src/com/facebook/signer.py"), linkSource1.getSource());
    assertEquals(Paths.get(relativePathToSrcDir + "/signer.py"), linkSource1.getTarget());

    MkdirAndSymlinkFileStep linkSource2 = (MkdirAndSymlinkFileStep) steps.get(5);
    assertEquals(Paths.get("src/com/facebook/key.properties"), linkSource2.getSource());
    assertEquals(Paths.get(relativePathToSrcDir + "/key.properties"), linkSource2.getTarget());

    Step seventhStep = steps.get(6);
    assertTrue(seventhStep instanceof ShellStep);
    ShellStep genruleCommand = (ShellStep) seventhStep;
    assertEquals("genrule", genruleCommand.getShortName());
    ImmutableMap<String, String> environmentVariables = genruleCommand.getEnvironmentVariables(
        executionContext);
    assertEquals(new ImmutableMap.Builder<String, String>()
        .put("APK", relativeToAbsolutePathFunction.apply(GEN_PATH.resolve("fb4a.apk")).toString())
        .put("OUT", expectedApkOutput).build(),
        environmentVariables);
    assertEquals(
        ImmutableList.of("/bin/bash", "-e", "-c", "python signer.py $APK key.properties > $OUT"),
        genruleCommand.getShellCommand(executionContext));

    EasyMock.verify(parser);
  }

  private ExecutionContext newEmptyExecutionContext() {
    return ExecutionContext.builder()
        .setConsole(new TestConsole())
        .setProjectFilesystem(new ProjectFilesystem(new File(".")) {
          @Override
          public Function<Path, Path> getAbsolutifier() {
            return IdentityPathAbsolutifier.getIdentityAbsolutifier();
          }

          @Override
          public Path resolve(Path path) {
            return path;
          }
        })
        .setEventBus(BuckEventBusFactory.newInstance())
        .setPlatform(Platform.LINUX) // Fix platform to Linux to use bash in genrule.
        .setEnvironment(ImmutableMap.copyOf(System.getenv()))
        .build();
  }
}
