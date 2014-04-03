/*************************GO-LICENSE-START*********************************
 * Copyright 2014 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *************************GO-LICENSE-END***********************************/

package com.thoughtworks.go.service;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import com.thoughtworks.go.config.exceptions.ConfigFileHasChangedException;
import com.thoughtworks.go.config.exceptions.ConfigMergeException;
import com.thoughtworks.go.domain.GoConfigRevision;
import com.thoughtworks.go.helper.ConfigFileFixture;
import com.thoughtworks.go.licensing.Edition;
import com.thoughtworks.go.util.SystemEnvironment;
import com.thoughtworks.go.util.TimeProvider;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class ConfigRepositoryTest {
    private ConfigRepository configRepo;
    private SystemEnvironment env;

    @Before
    public void setUp() throws IOException {
        env = new SystemEnvironment();
        FileUtils.deleteQuietly(env.getConfigRepoDir());
        configRepo = new ConfigRepository(env);
        configRepo.initialize();
    }

    @After
    public void tearDown() {
        FileUtils.deleteQuietly(env.getConfigRepoDir());
    }

    @Test
    public void shouldBeAbleToCheckin() throws Exception {
        configRepo.checkin(new GoConfigRevision("v1", "md5-v1", "user-name", "100.3.9", Edition.Free, new TimeProvider()));
        configRepo.checkin(new GoConfigRevision("v1 v2", "md5-v2", "user-name", "100.9.8", Edition.Enterprise, new TimeProvider()));
        assertThat(configRepo.getRevision("md5-v1").getContent(), is("v1"));
        assertThat(configRepo.getRevision("md5-v2").getContent(), is("v1 v2"));
    }

    @Test
    public void shouldFailWhenDoesNotFindARev() throws Exception {
        configRepo.checkin(new GoConfigRevision("v1", "md5-v1", "user-name", "100.3.9", Edition.Free, new TimeProvider()));
        try {
            configRepo.getRevision("some-random-revision");
            fail("should have failed as revision does not exist");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), is("There is no config version corresponding to md5: 'some-random-revision'"));
        }
    }

    @Test
    public void shouldUnderstandRevision_current_asLatestRevision() throws Exception {
        configRepo.checkin(new GoConfigRevision("v1", "md5-v1", "user-name", "100.3.9", Edition.Free, new TimeProvider()));
        configRepo.checkin(new GoConfigRevision("v1 v2", "md5-v2", "user-name", "100.9.8", Edition.Enterprise, new TimeProvider()));
        assertThat(configRepo.getRevision("current").getMd5(), is("md5-v2"));
    }

    @Test
    public void shouldReturnNullWhenThereAreNoCheckIns() throws GitAPIException, IOException {
        assertThat(configRepo.getRevision("current"), is(nullValue()));
    }

    @Test
    public void shouldNotCommitWhenNothingChanged() throws Exception {
        configRepo.checkin(new GoConfigRevision("v1", "md5-v1", "user-name", "100.3.9", Edition.Free, new TimeProvider()));
        configRepo.checkin(new GoConfigRevision("v1 v2", "md5-v1", "loser-name", "501.9.8", Edition.Enterprise, new TimeProvider()));//md5 is solely trusted
        Iterator<RevCommit> commitIterator = configRepo.revisions().iterator();
        int size = 0;
        while (commitIterator.hasNext()) {
            size++;
            commitIterator.next();
        }
        assertThat(size, is(1));
    }

    @Test
    public void shouldReturnPreviousCommitRevisionForGivenCommitRevision() throws Exception {
        configRepo.checkin(goConfigRevision("v1", "md5-1"));
        RevCommit previousCommit = configRepo.revisions().iterator().next();
        configRepo.checkin(new GoConfigRevision("v1 v2", "md5-2", "user-2", "13.2", Edition.Enterprise, new TimeProvider()));
        RevCommit latestCommit = configRepo.revisions().iterator().next();

        assertThat(configRepo.getPreviousCommit(previousCommit), is(nullValue()));
        assertThat(configRepo.getPreviousCommit(latestCommit), is(previousCommit));
    }

    @Test
    public void shouldShowDiffBetweenTwoConsecutiveGitRevisions() throws Exception {
        configRepo.checkin(goConfigRevision(ConfigFileFixture.configWithPipeline(ConfigFileFixture.SIMPLE_PIPELINE, 33), "md5-1"));
        RevCommit previousCommit = configRepo.revisions().iterator().next();
        configRepo.checkin(new GoConfigRevision(ConfigFileFixture.configWithPipeline(ConfigFileFixture.SIMPLE_PIPELINE, 60), "md5-2", "user-2", "13.2", Edition.Enterprise, new TimeProvider()));
        RevCommit latestCommit = configRepo.revisions().iterator().next();
        String configChangesLine1 = "-<cruise schemaVersion='33'>";
        String configChangesLine2 = "+<cruise schemaVersion='60'>";
        String actual = configRepo.findDiffBetweenTwoRevisions(latestCommit, previousCommit);
        assertThat(actual, containsString(configChangesLine1));
        assertThat(actual, containsString(configChangesLine2));
    }

    @Test
    public void shouldShowDiffBetweenAnyTwoGitRevisionsGivenTheirMd5s() throws Exception {
        configRepo.checkin(goConfigRevision(ConfigFileFixture.configWithPipeline(ConfigFileFixture.SIMPLE_PIPELINE, 33), "md5-1"));
        configRepo.checkin(new GoConfigRevision(ConfigFileFixture.configWithPipeline(ConfigFileFixture.SIMPLE_PIPELINE, 60), "md5-2", "user-2", "13.2", Edition.Free, new TimeProvider()));
        configRepo.checkin(new GoConfigRevision(ConfigFileFixture.configWithPipeline(ConfigFileFixture.SIMPLE_PIPELINE, 55), "md5-3", "user-1", "13.2", Edition.Free, new TimeProvider()));
        String configChangesLine1 = "-<cruise schemaVersion='33'>";
        String configChangesLine2 = "+<cruise schemaVersion='55'>";
        String actual = configRepo.findDiffBetweenTwoRevisions(configRepo.getRevCommitForMd5("md5-3"), configRepo.getRevCommitForMd5("md5-1"));
        assertThat(actual, containsString(configChangesLine1));
        assertThat(actual, containsString(configChangesLine2));
    }

    @Test
    public void shouldReturnNullForFirstCommit() throws Exception {
        configRepo.checkin(goConfigRevision("something", "md5-1"));
        RevCommit firstCommit = configRepo.revisions().iterator().next();
        String actual = configRepo.findDiffBetweenTwoRevisions(firstCommit, null);

        assertThat(actual, is(nullValue()));
    }

    @Test
    public void shouldShowDiffForAnyTwoConfigMd5s() throws Exception {
        configRepo.checkin(goConfigRevision(ConfigFileFixture.configWithPipeline(ConfigFileFixture.SIMPLE_PIPELINE, 33), "md5-1"));
        configRepo.checkin(new GoConfigRevision(ConfigFileFixture.configWithPipeline(ConfigFileFixture.SIMPLE_PIPELINE, 60), "md5-2", "user-2", "13.2", Edition.Enterprise, new TimeProvider()));
        configRepo.checkin(new GoConfigRevision(ConfigFileFixture.configWithPipeline(ConfigFileFixture.SIMPLE_PIPELINE, 55), "md5-3", "user-2", "13.2", Edition.Enterprise, new TimeProvider()));

        String configChangesLine1 = "-<cruise schemaVersion='33'>";
        String configChangesLine2 = "+<cruise schemaVersion='60'>";
        String configChangesLine3 = "+<cruise schemaVersion='55'>";

        String actual = configRepo.configChangesFor("md5-2", "md5-1");

        assertThat(actual, containsString(configChangesLine1));
        assertThat(actual, containsString(configChangesLine2));

        actual = configRepo.configChangesFor("md5-3", "md5-1");
        assertThat(actual, containsString(configChangesLine1));
        assertThat(actual, containsString(configChangesLine3));
    }

    @Test
    public void shouldRemoveUnwantedDataFromDiff() throws Exception {
        configRepo.checkin(goConfigRevision(ConfigFileFixture.configWithPipeline(ConfigFileFixture.SIMPLE_PIPELINE, 33), "md5-1"));
        String configXml = ConfigFileFixture.configWithPipeline(ConfigFileFixture.SIMPLE_PIPELINE, 60);
        configRepo.checkin(new GoConfigRevision(configXml, "md5-2", "user-2", "13.2", Edition.Enterprise, new TimeProvider()));
        String configChangesLine1 = "-<cruise schemaVersion='33'>";
        String configChangesLine2 = "+<cruise schemaVersion='60'>";
        String actual = configRepo.configChangesFor("md5-2", "md5-1");
        assertThat(actual, containsString(configChangesLine1));
        assertThat(actual, containsString(configChangesLine2));
        assertThat(actual, not(containsString("--- a/cruise-config.xml")));
        assertThat(actual, not(containsString("+++ b/cruise-config.xml")));
    }

    @Test
    public void shouldThrowExceptionIfRevisionNotFound() throws Exception {
        configRepo.checkin(goConfigRevision("v1", "md5-1"));
        try {
            configRepo.configChangesFor("md5-1", "md5-not-found");
            fail("Should have failed");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), is("There is no config version corresponding to md5: 'md5-not-found'"));
        }
        try {
            configRepo.configChangesFor("md5-not-found", "md5-1");
            fail("Should have failed");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), is("There is no config version corresponding to md5: 'md5-not-found'"));
        }
    }

    @Test
    public void shouldCreateBranchForARevCommit() throws Exception {
        configRepo.checkin(goConfigRevision("something", "md5-1"));
        RevCommit revCommit = configRepo.getCurrentRevCommit();
        configRepo.createBranch("branch1", revCommit);
        Ref branch = getBranch("branch1");
        assertThat(branch, is(notNullValue()));
        assertThat(branch.getObjectId(), is(revCommit.getId()));
    }

    @Test
    public void shouldCommitIntoGivenBranch() throws Exception {
        configRepo.checkin(goConfigRevision("something", "md5-1"));
        RevCommit revCommitOnMaster = configRepo.getCurrentRevCommit();

        String branchName = "branch1";
        configRepo.createBranch(branchName, revCommitOnMaster);

        String newConfigXML = "config-xml";
        GoConfigRevision configRevision = new GoConfigRevision(newConfigXML, "md5", "user", "version", Edition.Free, new TimeProvider());
        RevCommit branchRevCommit = configRepo.checkinToBranch(branchName, configRevision);

        assertThat(branchRevCommit, is(notNullValue()));
        assertThat(getLatestConfigAt(branchName), is(newConfigXML));
        assertThat(configRepo.getCurrentRevCommit(), is(revCommitOnMaster));
    }

    @Test
    public void shouldMergeNewCommitOnBranchWithHeadWhenThereIsNoConflict() throws Exception {
        String original = "first\nsecond\n";
        String changeOnBranch = "first\nsecond\nthird\n";
        String changeOnMaster = "1st\nsecond\n";
        String oldMd5 = "md5-1";
        configRepo.checkin(goConfigRevision(original, oldMd5));
        configRepo.checkin(goConfigRevision(changeOnMaster, "md5-2"));

        String mergedConfig = configRepo.getConfigMergedWithLatestRevision(goConfigRevision(changeOnBranch, "md5-3"), oldMd5);
        assertThat(mergedConfig, is("1st\nsecond\nthird\n"));
    }

    @Test
    public void shouldThrowExceptionWhenThereIsMergeConflict() throws Exception {
        String original = "first\nsecond\n";
        String nextUpdate = "1st\nsecond\n";
        String latestUpdate = "2nd\nsecond\n";
        configRepo.checkin(goConfigRevision(original, "md5-1"));
        configRepo.checkin(goConfigRevision(nextUpdate, "md5-2"));
        RevCommit currentRevCommitOnMaster = configRepo.getCurrentRevCommit();
        try {
            configRepo.getConfigMergedWithLatestRevision(goConfigRevision(latestUpdate, "md5-3"), "md5-1");
            fail("should have bombed for merge conflict");
        } catch (ConfigMergeException e) {
            assertThat(e.getMessage(), is(ConfigFileHasChangedException.CONFIG_CHANGED_PLEASE_REFRESH));
        }

        List<Ref> branches = getAllBranches();
        assertThat(branches.size(), is(1));
        assertThat(branches.get(0).getName().endsWith("master"), is(true));
        assertThat(configRepo.getCurrentRevCommit(), is(currentRevCommitOnMaster));
    }

    @Test
    public void shouldBeOnMasterAndTemporaryBranchesDeletedAfterGettingMergeConfig() throws Exception {
        String original = "first\nsecond\n";
        String nextUpdate = "1st\nsecond\n";
        String latestUpdate = "first\nsecond\nthird\n";
        configRepo.checkin(goConfigRevision(original, "md5-1"));
        configRepo.checkin(goConfigRevision(nextUpdate, "md5-2"));
        RevCommit currentRevCommitOnMaster = configRepo.getCurrentRevCommit();

        String mergedConfig = configRepo.getConfigMergedWithLatestRevision(goConfigRevision(latestUpdate, "md5-3"), "md5-1");

        assertThat(mergedConfig, is("1st\nsecond\nthird\n"));
        List<Ref> branches = getAllBranches();
        assertThat(branches.size(), is(1));
        assertThat(branches.get(0).getName().endsWith("master"),is(true));
        assertThat(configRepo.getCurrentRevCommit(), is(currentRevCommitOnMaster));
    }

    @Test
    public void shouldSwitchToMasterAndDeleteTempBranches() throws Exception, GitAPIException {
        configRepo.checkin(goConfigRevision("v1", "md5-1"));
        configRepo.createBranch(ConfigRepository.BRANCH_AT_HEAD, configRepo.getCurrentRevCommit());
        configRepo.createBranch(ConfigRepository.BRANCH_AT_REVISION, configRepo.getCurrentRevCommit());
        configRepo.git().checkout().setName(ConfigRepository.BRANCH_AT_REVISION).call();
        assertThat(configRepo.git().getRepository().getBranch(), is(ConfigRepository.BRANCH_AT_REVISION));
        assertThat(configRepo.git().branchList().call().size(), is(3));
        configRepo.cleanAndResetToMaster();
        assertThat(configRepo.git().getRepository().getBranch(), is("master"));
        assertThat(configRepo.git().branchList().call().size(), is(1));
    }

    @Test
    public void shouldCleanAndResetToMasterDuringInitialization() throws Exception {
        configRepo.checkin(goConfigRevision("v1", "md5-1"));
        configRepo.createBranch(ConfigRepository.BRANCH_AT_REVISION, configRepo.getCurrentRevCommit());
        configRepo.git().checkout().setName(ConfigRepository.BRANCH_AT_REVISION).call();
        assertThat(configRepo.git().getRepository().getBranch(), is(ConfigRepository.BRANCH_AT_REVISION));

        new ConfigRepository(new SystemEnvironment()).initialize();

        assertThat(configRepo.git().getRepository().getBranch(), is("master"));
        assertThat(configRepo.git().branchList().call().size(), is(1));
    }

    @Test
    public void shouldCleanAndResetToMasterOnceMergeFlowIsComplete() throws Exception {
        String original = "first\nsecond\n";
        String changeOnBranch = "first\nsecond\nthird\n";
        String changeOnMaster = "1st\nsecond\n";
        String oldMd5 = "md5-1";
        configRepo.checkin(goConfigRevision(original, oldMd5));
        configRepo.checkin(goConfigRevision(changeOnMaster, "md5-2"));

        configRepo.getConfigMergedWithLatestRevision(goConfigRevision(changeOnBranch, "md5-3"), oldMd5);
        assertThat(configRepo.git().getRepository().getBranch(), is("master"));
        assertThat(configRepo.git().branchList().call().size(), is(1));            
    }

    private GoConfigRevision goConfigRevision(String fileContent, String md5) {
        return new GoConfigRevision(fileContent, md5, "user-1", "13.2", Edition.Free, new TimeProvider());
    }

    private String getLatestConfigAt(String branchName) throws GitAPIException, IOException {
        configRepo.git().checkout().setName(branchName).call();

        String content = configRepo.getCurrentRevision().getContent();

        configRepo.git().checkout().setName("master").call();

        return content;
    }

    Ref getBranch(String branchName) throws GitAPIException {
        List<Ref> branches = getAllBranches();
        for (Ref branch : branches) {
            if(branch.getName().endsWith(branchName)) {
                return branch;
            }
        }
        return null;
    }

    private List<Ref> getAllBranches() throws GitAPIException {
        return configRepo.git().branchList().setListMode(ListBranchCommand.ListMode.ALL).call();
    }
}