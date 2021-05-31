/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.codehaus.mojo.servicedocgen;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

import javax.ws.rs.Path;

import org.apache.commons.collections.CollectionUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.reporting.AbstractMavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.codehaus.mojo.servicedocgen.descriptor.OperationDescriptor;
import org.codehaus.mojo.servicedocgen.descriptor.ServiceDescriptor;
import org.codehaus.mojo.servicedocgen.descriptor.ServicesDescriptor;
import org.codehaus.mojo.servicedocgen.generation.ServicesGenerator;
import org.codehaus.mojo.servicedocgen.generation.velocity.VelocityServicesGenerator;

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaAnnotation;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaSource;

/**
 * {@link AbstractMojo Maven Plugin} to automatically generate documentation for services of the current project.
 * <ol>
 * <li>Scans the current projects source code for (JAX-RS annotated) services that match the RegEx configured by
 * <code>classnameRegex</code>.</li>
 * <li>Analyzes the services from source-code (extract JavaDoc, etc.) and byte-code (resolve generic parameters, etc.)
 * and create intermediate meta-data as {@link ServicesDescriptor}.</li>
 * <li>Generates documentation from the collected meta-data (by default as HTML from a velocity template shipped with
 * this plugin but can be overridden via configuration parameters).</li>
 * </ol>
 * scan and analyze services from the current project via source-code and byte-code analysis. Creates
 *
 * @author hohwille
 */
@Mojo( name = "generate", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresProject = true, requiresDirectInvocation = false, executionStrategy = "once-per-session", requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME )
public class ServiceDocGenReport
    extends AbstractMavenReport
{

    /**
     * The directory where the generated service documentation will be written to.
     */
    @Parameter( defaultValue = "servicedoc" )
    private String reportFolder;

    /**
     * The fully qualified classname of the service to generate. Empty for auto-discovery (default).
     */
    @Parameter
    private String serviceClassName;

    /**
     * The regex {@link Pattern} that service classes have to match.
     */
    @Parameter( defaultValue = ".*Service.*" )
    private String classnameRegex;

    private Pattern classnamePattern;

    @Parameter( required = false )
    private ServicesDescriptor descriptor;

    @Parameter( defaultValue = "${project.runtimeClasspathElements}", readonly = true )
    private List<String> runtimeClasspathElements;

    @Parameter( defaultValue = "org/codehaus/mojo/servicedocgen/generation/velocity" )
    private String templatePath;

    @Parameter( required = false )
    private List<ServiceDocGenTemplate> templates;

    @Parameter( defaultValue = "${project.build.sourceEncoding}" )
    private String sourceEncoding;
    
    @Parameter( defaultValue = "${project.reporting.outputDirectory}", readonly = true, required = true )
    protected File outputDirectory;

    /**
     * Set to <code>true</code> if you want to introspect fields of Java beans and <code>false</code> for getters.
     */
    @Parameter( defaultValue = "false" )
    private boolean introspectFields;

    private ClassLoader projectClassloader;

    private JavaProjectBuilder builder;

    private List<JavaClass> serviceClasses;

    private boolean generatingSite;

    /**
     * {@inheritDoc}
     */
    @Override
    public String getOutputName()
    {
        return "servicedoc/index";
    }

    protected ResourceBundle getBundle( Locale locale )
    {

        return ResourceBundle.getBundle( "servicedocgen-report", locale, getClass().getClassLoader() );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName( Locale locale )
    {
        return getBundle( locale ).getString( "report.servicedocgen.name" );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDescription( Locale locale )
    {
        return getBundle( locale ).getString( "report.servicedocgen.description" );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isExternalReport()
    {
        return true;
    }
    
    protected String getOutputDirectoryPath()
    {
        return this.outputDirectory.getAbsolutePath();
    }

    private List<ServiceDocGenTemplate> getTemplates()
    {
        if( CollectionUtils.isEmpty( this.templates ) )
        {
            this.templates = new ArrayList<ServiceDocGenTemplate>();
            ServiceDocGenTemplate template = new ServiceDocGenTemplate( "Service-Documentation.html.vm" );
            template.setOutputName( "index.html" );
            this.templates.add( template );
            this.templates.add( new ServiceDocGenTemplate( "OpenApi.yaml.vm" ) );
            this.templates.add( new ServiceDocGenTemplate( "SwaggerUI.html.vm" ) );
        }
        return this.templates;
    }

    private JavaProjectBuilder getBuilder() {
        if (this.builder == null) {
            this.builder = new JavaProjectBuilder();
            if ( !Util.isEmpty( this.sourceEncoding ) )
            {
                 this.builder.setEncoding( this.sourceEncoding );
            }
        }
        return this.builder;
    }

    private List<JavaClass> getServiceClasses() {

        if (this.serviceClasses == null) {
            try
            {
                this.serviceClasses = scanServices( getBuilder() );
            }
            catch ( IOException e )
            {
                throw new RuntimeException( "Unexpected I/O error!", e );
            }
        }
        return this.serviceClasses;
    }

    private Pattern getClassnamePattern()
    {
        if (this.classnamePattern == null)
        {
          this.classnamePattern = Pattern.compile( this.classnameRegex );
        }
        return this.classnamePattern;
    }

    @Override
    public void execute()
        throws MojoExecutionException
    {
        try
        {
            this.generatingSite = false;
            generateReport();
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Unexpected Error!", e );
        }
    }

    @Override
    public boolean canGenerateReport()
    {
        return !getServiceClasses().isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void executeReport( Locale locale )
        throws MavenReportException
    {
        try
        {
            this.generatingSite = true;
            generateReport();
        }
        catch ( Exception e )
        {
            throw new MavenReportException( "Unexpected Error!", e );
        }
    }

    private void generateReport()
        throws Exception
    {
        if ( getServiceClasses().isEmpty() )
        {
            getLog().info( "No services found - omitting service documentation generation." );
            return;
        }
        Analyzer analyzer = new Analyzer( getLog(), this.project, getProjectClassloader(), this.builder, this.descriptor,
            this.introspectFields );
        ServicesDescriptor services = analyzer.createServicesDescriptor( getServiceClasses() );
        sortServiceOperationsByPath( services );

        String openApiUrl = "";
        for( ServiceDocGenTemplate template : this.getTemplates() )
        {
            if( template.getTemplateName().equals( "OpenApi.yaml.vm" ) || template.getTemplateName().equals( "OpenApi.json.vm" ) )
            {
                openApiUrl = template.getOutputNameWithFallback();
                break;
            }
        }

        for( ServiceDocGenTemplate template : getTemplates() )
        {
            String outputName = template.getOutputNameWithFallback();
            String templateName = template.getTemplateName();
            getLog().info( "Generating output file " + outputName + " for " + templateName + "..." );
            ServicesGenerator generator =
                new VelocityServicesGenerator( Util.appendPath( this.templatePath, templateName ) );
            File reportDirectory = new File( this.getOutputDirectoryPath(), this.reportFolder );
            if ( !reportDirectory.isDirectory() )
            {
                boolean ok = reportDirectory.mkdirs();
                if ( !ok )
                {
                    throw new MojoExecutionException( "Could not create directory " + reportDirectory );
                }
            }
            generator.generate( services, reportDirectory, outputName, openApiUrl );
        }
    }

    private List<JavaClass> scanServices( JavaProjectBuilder builder )
        throws IOException
    {
        List<JavaClass> serviceClassList = new ArrayList<JavaClass>();
        for ( String sourceDir : this.project.getCompileSourceRoots() )
        {
            File sourceFolder = new File( sourceDir );
            if ( sourceFolder.isDirectory() )
            {
                builder.addSourceFolder( sourceFolder );
                if ( this.serviceClassName == null )
                {
                    scanJavaFilesRecursive( sourceFolder, builder, serviceClassList );
                }
            }
        }
        if ( this.serviceClassName != null )
        {
            JavaClass type = builder.getClassByName( this.serviceClassName );
            boolean isService = isServiceClass( type );
            if ( isService )
            {
                serviceClassList.add( type );
                getLog().info( "Discovered service: " + type );
            }
        }
        return serviceClassList;
    }

    private void scanJavaFilesRecursive( File sourceDir, JavaProjectBuilder builder, List<JavaClass> serviceClasses )
        throws IOException
    {
        File[] children = sourceDir.listFiles();
        if ( children == null )
        {
            getLog().debug( "Directory does not exist: " + sourceDir );
            return;
        }
        for ( File file : children )
        {
            if ( file.isDirectory() )
            {
                scanJavaFilesRecursive( file, builder, serviceClasses );
            }
            else if ( file.getName().endsWith( ".java" ) )
            {
                try
                {
                    JavaSource source = builder.addSource( file );
                    for ( JavaClass type : source.getClasses() )
                    {
                        boolean isService = isServiceClass( type );
                        if ( isService )
                        {
                            serviceClasses.add( type );
                        }
                    }
                }
                catch ( Exception e )
                {
                    getLog().debug( "Error parsing file: " + file, e );
                }
            }
        }

    }

    private boolean isServiceClass( JavaClass type )
    {
        if ( getClassnamePattern().matcher( type.getName() ).matches() )
        {
            getLog().debug( "Class matches: " + type.getName() );
            for ( JavaAnnotation annotation : type.getAnnotations() )
            {
                if ( Path.class.getName().equals( annotation.getType().getFullyQualifiedName() ) )
                {
                    getLog().info( "Found service: " + type.getName() );
                    return true;
                }
            }
        }
        return false;
    }

    private ClassLoader getProjectClassloader()
        throws MojoExecutionException
    {
        if ( this.projectClassloader == null )
        {
            this.projectClassloader = new URLClassLoader( buildClasspathUrls(), this.getClass().getClassLoader() );
        }
        return this.projectClassloader;
    }

    private URL[] buildClasspathUrls()
        throws MojoExecutionException
    {
        List<URL> urls = new ArrayList<URL>( this.runtimeClasspathElements.size() );
        for ( String element : this.runtimeClasspathElements )
        {
            try
            {
                URL url = new File( element ).toURI().toURL();
                urls.add( url );
                getLog().debug( "Adding to classloader: " + url.toString() );
            }
            catch ( MalformedURLException e )
            {
                throw new MojoExecutionException( "Unable to access project dependency: " + element, e );
            }
        }
        return urls.toArray( new URL[urls.size()] );
    }

    private void sortServiceOperationsByPath( ServicesDescriptor services )
    {
        for ( ServiceDescriptor service : services.getServices() )
        {
            List<OperationDescriptor> operations = service.getOperations();
            Collections.sort( operations, new Comparator<OperationDescriptor>()
            {

                @Override
                public int compare( OperationDescriptor o1, OperationDescriptor o2 )
                {
                    return o1.getPath().compareTo( o2.getPath() );
                }

            } );
        }
    }

}