package me.allenz.zlog;

import java.util.Enumeration;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * A configurator that parses zlog.properties for loading zlog configures.
 * 
 * @author Allenz
 * @since 0.2.0-RELEASE
 */
class PropertyConfigurator {

	private static final String DEBUG_PREFIX = "debug";
	private static final String ROOT_PREFIX = "root";
	private static final String LOGGER_PREFIX = "logger.";
	private static final Pattern PACKAGE_NAME_PATTERN = Pattern
			.compile("([\\p{L}_$][\\p{L}\\p{N}_$]*\\.)*[\\p{L}_$][\\p{L}\\p{N}_$]*");

	private static final Logger internalLogger = ConfigureRepository
			.getInternalLogger();

	private Properties properties;

	public PropertyConfigurator(final Properties properties) {
		this.properties = properties;
	}

	public void doConfigure(final ConfigureRepository repository) {
		repository.resetToDefault();
		loadDebugConfig();// check if need to close internal logging first
							// before parsing other properties
		loadRootLoggerConfig(repository);
		loadLoggerConfigs(repository);
	};

	private void loadDebugConfig() {
		final String value = (String) properties.get(DEBUG_PREFIX);
		if (value == null) {
			return;
		}
		final Boolean debug = Utils.parseBoolean(value);
		if (!debug) {
			ConfigureRepository.setInternalLogLevel(LogLevel.OFF);
		}
	}

	private void loadRootLoggerConfig(final ConfigureRepository repository) {
		final String value = (String) properties.get(ROOT_PREFIX);
		if (value == null) {
			return;
		}
		final LoggerConfig loggerConfig = parseLoggerConfig(ROOT_PREFIX, value);
		if (loggerConfig == null) {
			internalLogger.verbose(
					"parse root logger configure failed, use default: %s",
					repository.getRootLoggerConfig());
		} else {
			repository.setRootLoggerConfig(loggerConfig);
			internalLogger.verbose("root logger: %s", loggerConfig);
		}
	}

	private void loadLoggerConfigs(final ConfigureRepository repository) {
		final int loggerPrefixLength = LOGGER_PREFIX.length();
		for (final Enumeration<?> names = properties.propertyNames(); names
				.hasMoreElements();) {
			final String propertyName = (String) names.nextElement();
			if (Utils.isEmpty(propertyName)
					|| propertyName.length() <= loggerPrefixLength) {
				continue;
			}
			if (propertyName.startsWith(LOGGER_PREFIX)) {
				final String name = propertyName.substring(
						LOGGER_PREFIX.length(), propertyName.length());
				// check if the logger's name is a legal java package or
				// class name before parse the property value
				if (PACKAGE_NAME_PATTERN.matcher(name).matches()) {
					final String propertyValue = properties
							.getProperty(propertyName);
					final LoggerConfig loggerConfig = parseLoggerConfig(name,
							propertyValue);
					if (loggerConfig != null) {
						internalLogger.verbose("logger '%s': %s", name,
								loggerConfig);
						repository.addLoggerConfig(loggerConfig);
					}
				} else {
					internalLogger
							.verbose(
									"name '%s' is illegal, it should be package or class fullname, skip",
									name);
				}
			}
		}
	}

	private LoggerConfig parseLoggerConfig(final String name,
			final String propertyValue) {
		if (Utils.isEmpty(propertyValue)) {
			internalLogger.verbose(
					"property value of logger '%s' is empty, skip", name);
			return null;
		}
		String levelStr;
		String tagStr = null;
		String threadStr = null;
		final LogLevel level;
		final String tag;
		Boolean thread = false;
		// try to split property value into 3 parts: 'log level', 'log tag' and
		// 'show thread'
		final int comma = propertyValue.indexOf(",");
		if (comma == -1) {// there's no ',' in value, consider all this value is
							// a 'log level'
			levelStr = propertyValue;
		} else {
			final int lastComma = propertyValue.lastIndexOf(",");
			if (lastComma == comma) {// there's only 2 parts in value, split
										// value into 2 parts:'log level' and
										// 'log tag'
				levelStr = propertyValue.substring(0, comma);
				tagStr = propertyValue.substring(comma + 1);
			} else {// split value into 3 parts: 'log level', 'log tag' and
					// 'show thread'
				levelStr = propertyValue.substring(0, comma);
				tagStr = propertyValue.substring(comma + 1, lastComma);
				threadStr = propertyValue.substring(lastComma + 1);
				// parse 'show thread' string first, if it can not be parsed to
				// boolean value, consider it as a part of tag
				thread = Utils.parseBoolean(threadStr);
				if (thread == null) {
					tagStr += threadStr;
				}
			}
		}
		if (Utils.isEmpty(levelStr)) {
			return null;
		} else {
			level = parseLogLevel(levelStr);
			if (level == null) {
				internalLogger.verbose("logger %s level '%s' is illegal, skip",
						name, levelStr);
				return null;
			}
		}
		tag = parseTag(name, tagStr);
		return new LoggerConfig(name, tag, level, thread);
	}

	private LogLevel parseLogLevel(final String level) {
		try {
			return LogLevel.valueOf(level.toUpperCase(Locale.ENGLISH));
		} catch (final IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * 
	 * Parse the logger tag in property value, if the tag is empty we consider
	 * there's no tag for the logger and the logger will use it's class name as
	 * the tag.
	 * <p>
	 * 
	 * There's no obviously limit for the length of android log tag. The size of
	 * 'tag + message' should not be greater than 4073 bytes, and the exceed
	 * bytes won't be written to internal log buffer.
	 * <p>
	 * 
	 * In {@link android.util.Log#isLoggable(String tag, int level)}, the size
	 * of argument 'tag' should be no greater than 23 bytes or we will receive
	 * an exception, simplely not to call this method to avoid it.
	 * <p>
	 * 
	 * Reference:
	 * 
	 * <pre>
	 * http://developer.android.com/reference/android/util/Log.html
	 * http://www.slf4j.org/android/
	 * http://stackoverflow.com/questions/4126815/android-logging-levels
	 * https://github.com/android/platform_frameworks_base/blob/master/core/jni/android_util_Log.cpp
	 * https://github.com/android/platform_bionic/blob/master/libc/include/sys/system_properties.h
	 * https://android.googlesource.com/kernel/common.git/+/android-3.4/drivers/staging/android/logger.h
	 * https://android.googlesource.com/kernel/common.git/+/android-3.4/drivers/staging/android/logger.c
	 * </pre>
	 * 
	 * @param name
	 *            the name of the logger
	 * @param tag
	 *            the tag of the logger
	 * @return If the length of the tag is zero return {@code null}, otherwise
	 *         return the tag.
	 */
	private String parseTag(final String name, final String tag) {
		return Utils.isEmpty(tag) ? null : tag;
	}

}