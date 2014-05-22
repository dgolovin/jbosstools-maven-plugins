/**
 * Copyright (c) 2012, Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributor:
 *     Mickael Istria (Red Hat, Inc.) - initial API and implementation
 *     Denis Golovin (Exadel, Inc) - target to runnable implementation
 */
package org.jboss.tools.tycho.targets;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.sisu.equinox.EquinoxServiceFactory;
import org.eclipse.tycho.BuildOutputDirectory;
import org.eclipse.tycho.artifacts.TargetPlatform;
import org.eclipse.tycho.core.TargetPlatformConfiguration;
import org.eclipse.tycho.core.facade.TargetEnvironment;
import org.eclipse.tycho.core.resolver.shared.MavenRepositoryLocation;
import org.eclipse.tycho.core.utils.TychoProjectUtils;
import org.eclipse.tycho.osgi.adapters.MavenLoggerAdapter;
import org.eclipse.tycho.p2.facade.RepositoryReferenceTool;
import org.eclipse.tycho.p2.resolver.TargetDefinitionFile;
import org.eclipse.tycho.p2.resolver.facade.P2ResolutionResult;
import org.eclipse.tycho.p2.resolver.facade.P2Resolver;
import org.eclipse.tycho.p2.resolver.facade.P2ResolverFactory;
import org.eclipse.tycho.p2.resolver.facade.P2ResolutionResult.Entry;
import org.eclipse.tycho.p2.target.facade.TargetDefinition;
import org.eclipse.tycho.p2.target.facade.TargetPlatformConfigurationStub;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.InstallableUnitLocation;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Location;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Repository;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Unit;
import org.eclipse.tycho.p2.tools.RepositoryReferences;
import org.eclipse.tycho.p2.tools.director.shared.DirectorCommandException;
import org.eclipse.tycho.p2.tools.director.shared.DirectorRuntime;
import org.eclipse.tycho.p2.tools.mirroring.facade.IUDescription;
import org.omg.CosNaming.NamingContextExtPackage.AddressHelper;

/**
 * @goal mirror-target-to-runnable
 */
public final class TargetToRunnableMojo extends AbstractMojo {

    /**
     * @parameter expression="${project}"
     * @readonly
     */
    private MavenProject project;

    /**
     * @parameter expression="${session}"
     * @readonly
     */
    private MavenSession session;

    MavenProject getProject() {
        return project;
    }

    MavenSession getSession() {
        return session;
    }

    /** @component */
    private EquinoxServiceFactory osgiServices;

    /** @component */
    private Logger logger;

    /** @parameter default-value="JavaSE-1.6" */
    private String executionEnvironment;

    /**
     * @parameter expression="${project.build.directory}/${project.artifactId}.install"
     */
    private File outputRepository;

    /** @parameter default-value="DefaultProfile" */
    private String profile;

    /** @parameter default-value="true" */
    private boolean installFeatures;
    
    /**
     * @parameter expression="${project.basedir}/${project.artifactId}.target"
     */
    private File sourceTargetFile;

    // TODO extract methods
    public void execute() throws MojoExecutionException, MojoFailureException {

        DirectorRuntime director = getDirectorRuntime();
     
        try {
        	TargetDefinitionFile target = TargetDefinitionFile.read(sourceTargetFile);
    		DirectorRuntime.Command command = director.newInstallCommand();
    		List<URI> repos = new ArrayList<URI>();
        	for (final Location loc : target.getLocations()) {
        		List<? extends TargetDefinition.Repository> targetRepos = ((InstallableUnitLocation)loc).getRepositories();
        		for (Repository repository : targetRepos) {
        			repos.add(repository.getLocation());
				}
        	}
    		command.addMetadataSources(repos);
    		command.addArtifactSources(repos);
    		
    		TargetPlatformConfigurationStub tpConfiguration = new TargetPlatformConfigurationStub();
            List<TargetEnvironment> environments = Collections.singletonList(TargetEnvironment.getRunningEnvironment());
            tpConfiguration.setEnvironments(environments);
            tpConfiguration.addTargetDefinition(target);
            
            P2ResolverFactory factory = this.osgiServices.getService(P2ResolverFactory.class);
            P2Resolver tpResolver = factory.createResolver(new MavenLoggerAdapter(this.logger, getLog().isDebugEnabled()));
            tpResolver.setEnvironments(environments);
            
            for (Location loc : target.getLocations()) {
            	if (loc instanceof InstallableUnitLocation) {
            		InstallableUnitLocation p2Loc = (InstallableUnitLocation) loc;
            		for (Unit unit : p2Loc.getUnits()) {
            			// resolve everything in TP
            			tpResolver.addDependency(P2Resolver.TYPE_INSTALLABLE_UNIT, unit.getId(), "[" + unit.getVersion() + "," + unit.getVersion() + "]");
            		}
            	}
            }
            P2ResolutionResult result = tpResolver.resolveMetadata(tpConfiguration, executionEnvironment);

            Set<String> sourcesFound = new HashSet<String>();
            Set<String> regularArtifacts = new HashSet<String>();
        	for (Entry entry : result.getArtifacts()) {
        		if (entry.getId().endsWith(".source")) {
        			sourcesFound.add(entry.getId().substring(0, entry.getId().length() - ".source".length()));
        		} else if (entry.getId().endsWith(".source.feature.group")) {
        			sourcesFound.add(entry.getId().replace(".source.feature.group", ".feature.group"));
        		} else {
        			regularArtifacts.add(entry.getId());
        		}
        	}
        	
        	Set<String> artifactsWithoutSources = new HashSet<String>(regularArtifacts);
        	artifactsWithoutSources.removeAll(sourcesFound);
        	
        	if (!artifactsWithoutSources.isEmpty()) {
        		TargetPlatformConfigurationStub sites = new TargetPlatformConfigurationStub();
        		
        		for (Location loc : target.getLocations()) {
        			if (loc instanceof InstallableUnitLocation) {
        				InstallableUnitLocation location = (InstallableUnitLocation)loc;
        				for (Repository repo : location.getRepositories()) {
        					sites.addP2Repository(new MavenRepositoryLocation(repo.getId(), repo.getLocation()));
        				}
        			}
        		}
        		TargetPlatform sitesTP = factory.getTargetPlatformFactory().createTargetPlatform(sites, new MockExecutionEnvironment(), null, null);
        		for (String unitId : artifactsWithoutSources) {
        			String sourceUnitId = unitId + ".source";
        			// ignore .feature.source
        			P2ResolutionResult resolvedSource = tpResolver.resolveInstallableUnit(sitesTP, sourceUnitId, null);
        			if (resolvedSource.getArtifacts().size() > 0 || resolvedSource.getNonReactorUnits().size() > 0) {
        				command.addUnitToInstall(sourceUnitId + "/" + resolvedSource.getArtifacts().iterator().next().getVersion());
        			}
        		}
        	}

        	for (final Location loc : target.getLocations()) {
            	if (loc instanceof InstallableUnitLocation) {
            		for (Unit unit : ((InstallableUnitLocation)loc).getUnits()) {
            			command.addUnitToInstall(unit.getId()+"/"+ unit.getVersion());
            		}
            	}
        	}
        	
		
    		command.setDestination(outputRepository);
    		command.setProfileName(profile);
    		command.setEnvironment(TargetEnvironment.getRunningEnvironment());
    		command.setInstallFeatures(installFeatures);
    		getLog().info(
    				"Converting target to runnable " 
    						+ outputRepository.getAbsolutePath());

            command.execute();

        } catch (DirectorCommandException e) {
            throw new MojoFailureException("Converting target to runnable failed", e);
        }

    }

    private DirectorRuntime getDirectorRuntime() throws MojoFailureException, MojoExecutionException {
        // director from Tycho's OSGi runtime
        return osgiServices.getService(DirectorRuntime.class);
    }
}
