/*
 * SchemaLoader.java
 *
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2017 "Tigase, Inc." <office@tigase.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */
package tigase.db.util;

import tigase.auth.CredentialsDecoderBean;
import tigase.auth.CredentialsEncoderBean;
import tigase.component.DSLBeanConfigurator;
import tigase.conf.ConfigBuilder;
import tigase.conf.ConfigHolder;
import tigase.conf.ConfigWriter;
import tigase.db.*;
import tigase.kernel.DefaultTypesConverter;
import tigase.kernel.core.Kernel;
import tigase.osgi.util.ClassUtilBean;
import tigase.util.Version;
import tigase.util.reflection.ReflectionHelper;
import tigase.util.ui.console.CommandlineParameter;
import tigase.util.ui.console.ParameterParser;
import tigase.xmpp.jid.BareJID;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static tigase.db.util.SchemaManager.COMMON_SCHEMA_ID;

/**
 * @author andrzej
 */
public abstract class SchemaLoader<P extends SchemaLoader.Parameters> {

	protected static final Logger log = Logger.getLogger(SchemaLoader.class.getName());

	public static enum Result {
		ok,
		error,
		warning,
		skipped
	}
	private String type;

	private static List<CommandlineParameter> getDbTypeDependentParameters(String type) {
		SchemaLoader loader = newInstance(type);
		return loader.getCommandlineParameters();
	}

	public static List<CommandlineParameter> getMainCommandlineParameters(boolean forceNotRequired) {
		String[] supportedTypes = (String[]) getSchemaLoaderInstances().flatMap(
				loader -> loader.getSupportedTypes().stream()).map(x -> (String) x).sorted().toArray(String[]::new);
		return Arrays.asList(new CommandlineParameter.Builder("T",
															  DBSchemaLoader.PARAMETERS_ENUM.DATABASE_TYPE.getName()).description(
				"Database server type")
									 .options(supportedTypes)
									 //.defaultValue(DBSchemaLoader.PARAMETERS_ENUM.DATABASE_TYPE.getDefaultValue())
									 .valueDependentParametersProvider(SchemaLoader::getDbTypeDependentParameters)
									 .required(!forceNotRequired)
									 .build());
	}

	private static Stream<Class<?>> getSchemaLoaderClasses() {
		return ClassUtilBean.getInstance()
				.getAllClasses()
				.stream()
				.filter(clazz -> SchemaLoader.class.isAssignableFrom(clazz))
				.filter(clazz -> !Modifier.isAbstract(clazz.getModifiers()));
	}

	private static Stream<SchemaLoader> getSchemaLoaderInstances() {
		return getSchemaLoaderClasses().map(clazz -> {
			SchemaLoader loader = null;
			try {
				loader = (SchemaLoader) clazz.newInstance();
			} catch (IllegalAccessException | InstantiationException e) {
				log.log(Level.WARNING, "Error creating instance", e);
			}
			return loader;
		}).filter(Objects::nonNull);
	}

	/**
	 * Main method allowing pass arguments to the class and setting all logging to be printed to console.
	 *
	 * @param args key-value (in the form of {@code "-<variable> value"}) parameters.
	 */
	public static void main(String[] args) {
		ParameterParser parser = new ParameterParser(true);

		parser.addOptions(getMainCommandlineParameters(false));

		Properties properties = null;

		if (null == args || args.length == 0 || (properties = parser.parseArgs(args)) == null) {
			System.out.println(parser.getHelp());
			System.exit(0);
		} else {
			System.out.println("properties: " + properties);
		}

		String type = properties.getProperty(DBSchemaLoader.PARAMETERS_ENUM.DATABASE_TYPE.getName());

		SchemaLoader dbHelper = newInstance(type);

		Parameters params = dbHelper.createParameters();
		params.setProperties(properties);

		dbHelper.execute(params);
	}

	public static SchemaLoader newInstance(String type) {
		if (type == null) {
			throw new RuntimeException("Missing dbType property");
		}
		SchemaLoader schemaLoader = getSchemaLoaderInstances().filter(instance -> instance.isSupported(type))
				.findAny()
				.get();
		schemaLoader.setType(type);
		return schemaLoader;
	}

	public static SchemaLoader newInstanceForURI(String uri) {
		int idx = uri.indexOf(":");
		if (idx < 0) {
			throw new RuntimeException("Unsupported URI");
		}
		String type = uri.substring(0, idx);
		return newInstance(type);
	}

	public abstract P createParameters();

	public abstract void execute(Parameters params);

	public abstract void init(P props, Optional<SchemaManager.RootCredentialsCache> rootCredentialsCache);

	public void init(P props) {
		init(props, Optional.empty());
	}

	public abstract List<String> getSupportedTypes();

	public boolean isSupported(String dbType) {
		return getSupportedTypes().contains(dbType);
	}

	public abstract String getDBUri();

	public abstract List<CommandlineParameter> getSetupOptions();

	public abstract List<CommandlineParameter> getCommandlineParameters();

	/**
	 * Method validates whether the connection can at least be eI stablished. If yes then appropriate flag is set.
	 */
	public abstract Result validateDBConnection();

	/**
	 * Method, if the connection is validated by {@code validateDBConnection}, checks whether desired database exists.
	 * If not it creates such database using {@code *-installer-create-db.sql} schema file substituting it's variables
	 * with ones provided.
	 */
	public abstract Result validateDBExists();

	public abstract Result postInstallation();

	protected String getConfigString() throws IOException {
		String dataSourceUri = getDBUri();

		ConfigBuilder builder = new ConfigBuilder();
		builder.withBean(ds -> ds.name("dataSource").withBean(def -> def.name("default").with("uri", dataSourceUri)));

		try (StringWriter writer = new StringWriter()) {
			new ConfigWriter().write(writer, builder.build());
			return writer.toString();
		}
	}

	public Result printInfo() {
		String configStr = null;
		try {
			configStr = getConfigString();
		} catch (IOException ex) {
			// should not happen
			configStr = "Failure: " + ex.getMessage();
		}
		Logger.getLogger(this.getClass().getCanonicalName())
				.log(Level.INFO, "\n\nDatabase " + ConfigHolder.TDSL_CONFIG_FILE_DEF + " configuration:\n{0}\n",
					 new Object[]{configStr});
		return Result.ok;
	}

	/**
	 * Method attempts to add XMPP admin user account to the database using {@code AuthRepository}.
	 */
	public abstract Result addXmppAdminAccount(SchemaManager.SchemaInfo schemaInfo);

	/**
	 * Methods attempt to write to database loaded schema version for particular component
	 *
	 * @param component name of the component for which version should be set
	 * @param version value which should be associated with the component
	 *
	 * @return a {@link Result} object indicating whether the call was successful
	 */
	public abstract Result setComponentVersion(String component, String version);

	public abstract Optional<Version> getComponentVersionFromDb(String component);

	/**
	 * Method checks whether the connection to the database is possible and that database of specified name exists. If
	 * yes then a schema file from properties is loaded.
	 *
	 * @param fileName set of {@code String} with path to file
	 */
	public abstract Result loadSchemaFile(String fileName);

	public abstract Result shutdown();

	public Result loadCommonSchema() {
		return loadSchema(new SchemaManager.SchemaInfo(COMMON_SCHEMA_ID, "Common Schema", Collections.emptyList()),
						  null);
	}

	public abstract Result loadSchema(SchemaManager.SchemaInfo schemaInfo, String version);
	
	public abstract Result destroyDataSource();

	protected <T extends DataSource> Result addUsersToRepository(SchemaManager.SchemaInfo schemaInfo, T dataSource, Class<T> dataSourceClass, List<BareJID> jids, String password, Logger log) {
		return getDataSourceAwareClassesForSchemaInfo(schemaInfo, dataSourceClass).filter(
				AuthRepository.class::isAssignableFrom)
				.map(this::instantiateClass)
				.map(initializeDataSourceAwareFunction(dataSource, log))
				.map(this::castToAuthRepository)
				.map(this::initializeAuthRepository)
				.filter(Objects::nonNull)
				.findAny()
				.map(addUsersToRepositoryFunction(jids, password, log))
				.orElse(Result.error);
	}

	protected <DS extends DataSource> Stream<Class<DataSourceAware<DS>>> getDataSourceAwareClassesForSchemaInfo(
			SchemaManager.SchemaInfo schema, Class<DS> dataSourceIfc) {
		Stream<Class<DataSourceAware<DS>>> classes = schema.getRepositories()
				.stream()
				.map(repoInfo -> repoInfo.getImplementation())
				.filter(clazz -> DataSourceAware.class.isAssignableFrom(clazz))
				.filter(clazz -> ReflectionHelper.classMatchesClassWithParameters(clazz, DataSourceAware.class,
																				  new Type[]{dataSourceIfc}))
				.map(clazz -> (Class<DataSourceAware<DS>>) clazz);

		return classes;
	}

	protected <DSIFC extends DataSource, DS extends DSIFC> Stream<DataSourceAware> getInitializedDataSourceAwareForSchemaInfo(
			SchemaManager.SchemaInfo schema, Class<DSIFC> dataSourceIfc, DS dataSource, Logger log) {
		return getDataSourceAwareClassesForSchemaInfo(schema, dataSourceIfc).map(this::instantiateClass)
				.map(initializeDataSourceAwareFunction(dataSource, log));
	}

	protected AuthRepository castToAuthRepository(DataSourceAware dataSourceAware) {
		if (dataSourceAware instanceof AuthRepository) {
			return (AuthRepository) dataSourceAware;
		} else {
			log.log(Level.WARNING, "DataSourceAware does not implement AuthRepository interface");
			return null;
		}
	}

	protected AuthRepository initializeAuthRepository(AuthRepository authRepository) {
		if (authRepository instanceof AbstractAuthRepositoryWithCredentials) {
			AbstractAuthRepositoryWithCredentials repo = (AbstractAuthRepositoryWithCredentials) authRepository;
			Kernel kernel = new Kernel();
			kernel.registerBean(DefaultTypesConverter.class).exportable().exec();
			kernel.registerBean(DSLBeanConfigurator.class).exportable().exec();
			kernel.getInstance(DSLBeanConfigurator.class).setProperties(new HashMap<>());
			kernel.registerBean(CredentialsEncoderBean.class).exec();
			kernel.registerBean(CredentialsDecoderBean.class).exec();
			CredentialsEncoderBean encoderBean = kernel.getInstance(CredentialsEncoderBean.class);
			CredentialsDecoderBean decoderBean = kernel.getInstance(CredentialsDecoderBean.class);
			repo.setCredentialsCodecs(encoderBean, decoderBean);
		}
		return authRepository;
	}

	protected <T extends DataSource> Function<DataSourceAware<T>, DataSourceAware<T>> initializeDataSourceAwareFunction(T dataSource, Logger log) {
		return repo -> {
			try {
				repo.setDataSource(dataSource);
				return repo;
			} catch (Exception ex) {
				log.log(Level.WARNING, ex.getMessage());
				return null;
			}
		};
	}

	protected Function<AuthRepository, Result> addUsersToRepositoryFunction(List<BareJID> jids, String pwd,
																			Logger log) {
		return authRepository -> {
			if (authRepository == null) {
				return Result.error;
			}
			return jids.stream().map(jid -> {
				try {
					authRepository.addUser(jid, pwd);
					return Result.ok;
				} catch (TigaseDBException ex) {
					log.log(Level.WARNING, ex.getMessage());
					return Result.warning;
				}
			}).anyMatch(result -> result == Result.warning) ? Result.warning : Result.ok;
		};
	}

	protected <T> T instantiateClass(Class<T> clazz) {
		try {
			return (T) clazz.newInstance();
		} catch (Exception ex) {
			log.log(Level.WARNING, "Failed to create instance of " + clazz.getCanonicalName());
			return null;
		}
	}

	private <T extends DataSource> DataSourceAware<T> instantiateDataSourceAware(Class<?> clazz) {
		try {
			return (DataSourceAware<T>) clazz.newInstance();
		} catch (Exception ex) {
			log.log(Level.WARNING, "Failed to create instance of " + clazz.getCanonicalName());
			return null;
		}
	}

	protected String getType() {
		return type;
	}

	private void setType(String type) {
		this.type = type;
	}

	public interface Parameters {

		void parseUri(String uri);

		void setProperties(Properties props);

		List<BareJID> getAdmins();

		String getAdminPassword();

		void setAdmins(List<BareJID> admins, String password);

		void setDbRootCredentials(String username, String password);

		Level getLogLevel();

		void setLogLevel(Level level);
	}
}
