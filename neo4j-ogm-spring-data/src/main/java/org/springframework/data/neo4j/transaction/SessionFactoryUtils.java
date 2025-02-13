/*
 * Copyright 2011-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.neo4j.transaction;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.neo4j.ogm.exception.ConnectionException;
import org.neo4j.ogm.exception.CypherException;
import org.neo4j.ogm.exception.ResultProcessingException;
import org.neo4j.ogm.exception.TransactionException;
import org.neo4j.ogm.exception.core.InvalidDepthException;
import org.neo4j.ogm.exception.core.MappingException;
import org.neo4j.ogm.exception.core.MetadataException;
import org.neo4j.ogm.exception.core.MissingOperatorException;
import org.neo4j.ogm.exception.core.TransactionManagerException;
import org.neo4j.ogm.exception.core.UnknownStatementTypeException;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.TypeMismatchDataAccessException;
import org.springframework.data.neo4j.exception.Neo4jErrorStatusCodes;
import org.springframework.data.neo4j.exception.UncategorizedNeo4jException;
import org.springframework.transaction.support.ResourceHolderSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/**
 * Helper class featuring methods for Neo4j OGM Session handling, allowing for reuse of Session instances within
 * transactions. Also provides support for exception translation.
 * <p>
 * Mainly intended for internal use within the framework.
 *
 * @author Mark Angrish
 * @author Michael J. Simons
 */
public class SessionFactoryUtils {

	private static final Logger logger = LoggerFactory.getLogger(SessionFactoryUtils.class);

	/**
	 * @deprecated since 5.2, this has been a Noop since 4.2 and has no meaning since then. It will be removed in the next major version.
	 * @param session
	 */
	@Deprecated
	public static void closeSession(Session session) {}

	public static Session getSession(SessionFactory sessionFactory) throws IllegalStateException {

		Assert.notNull(sessionFactory, "No SessionFactory specified");

		SessionHolder sessionHolder = (SessionHolder) TransactionSynchronizationManager.getResource(sessionFactory);
		if (sessionHolder != null) {
			if (!sessionHolder.isSynchronizedWithTransaction()
					&& TransactionSynchronizationManager.isSynchronizationActive()) {
				sessionHolder.setSynchronizedWithTransaction(true);
				TransactionSynchronizationManager
						.registerSynchronization(new SessionSynchronization(sessionHolder, sessionFactory, false));
			}
			return sessionHolder.getSession();
		}

		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return null;
		}

		Session session = sessionFactory.openSession();

		logger.debug("Registering transaction synchronization for Neo4j Session");
		// Use same Session for further Neo4j actions within the transaction.
		// Thread object will get removed by synchronization at transaction completion.

		sessionHolder = new SessionHolder(session);
		sessionHolder.setSynchronizedWithTransaction(true);
		TransactionSynchronizationManager
				.registerSynchronization(new SessionSynchronization(sessionHolder, sessionFactory, true));
		TransactionSynchronizationManager.bindResource(sessionFactory, sessionHolder);

		return session;
	}

	/**
	 * Convert the given runtime exception to an appropriate exception from the {@code org.springframework.dao} hierarchy.
	 * Return null if no translation is appropriate: any other exception may have resulted from user code, and should not
	 * be translated.
	 *
	 * @param ex runtime exception that occurred
	 * @return the corresponding DataAccessException instance, or {@code null} if the exception should not be translated
	 */
	public static DataAccessException convertOgmAccessException(RuntimeException ex) {

		// TODO: The OGM should not be throwing common runtime exceptions as these mask user errors.
		// TODO: Instead we should be defining our own Exceptions that can then be translated into Spring Exceptions.
		if (ex instanceof IllegalStateException) {
			return new InvalidDataAccessApiUsageException(ex.getMessage(), ex);
		}

		if (ex instanceof InvalidDepthException) {
			return new InvalidDataAccessApiUsageException(ex.getMessage(), ex);
		}
		if (ex instanceof ResultProcessingException) {
			return new DataRetrievalFailureException(ex.getMessage(), ex);
		}
		if (ex instanceof MappingException) {
			return new InvalidDataAccessApiUsageException(ex.getMessage(), ex);
		}
		if (ex instanceof MetadataException) {
			return new TypeMismatchDataAccessException(ex.getMessage(), ex);
		}
		if (ex instanceof UnknownStatementTypeException) {
			return new InvalidDataAccessApiUsageException(ex.getMessage(), ex);
		}
		if (ex instanceof ConnectionException) {
			return new DataAccessResourceFailureException(ex.getMessage(), ex);
		}
		if (ex instanceof MissingOperatorException) {
			return new InvalidDataAccessApiUsageException(ex.getMessage(), ex);
		}
		if (ex instanceof TransactionException) {
			return new InvalidDataAccessApiUsageException(ex.getMessage(), ex);
		}
		if (ex instanceof TransactionManagerException) {
			return new InvalidDataAccessApiUsageException(ex.getMessage(), ex);
		}

		// All exceptions coming back from the database.
		if (ex instanceof CypherException) {
			String code = ((CypherException) ex).getCode();
			final Class<? extends DataAccessException> dae = Neo4jErrorStatusCodes.translate(code);

			if (dae != null) {
				try {
					final Constructor<? extends DataAccessException> constructor = dae.getDeclaredConstructor(String.class,
							Throwable.class);
					return constructor.newInstance(ex.getMessage(), ex);
				} catch (InstantiationException | IllegalAccessException | NoSuchMethodException
						| InvocationTargetException e) {
					return null;
				}
			}

			return new UncategorizedNeo4jException(ex.getMessage(), ex);
		}
		// If we get here, we have an exception that resulted from user code,
		// rather than the persistence provider, so we return null to indicate
		// that translation should not occur.
		return null;
	}

	private static class SessionSynchronization extends ResourceHolderSynchronization<SessionHolder, SessionFactory>
			implements Ordered {

		private final boolean newSession;

		SessionSynchronization(SessionHolder sessionHolder, SessionFactory sessionFactory, boolean newSession) {
			super(sessionHolder, sessionFactory);
			this.newSession = newSession;
		}

		@Override
		public int getOrder() {
			return 900;
		}

		@Override
		public void flushResource(SessionHolder resourceHolder) {}

		@Override
		protected boolean shouldUnbindAtCompletion() {
			return this.newSession;
		}

		@Override
		protected boolean shouldReleaseAfterCompletion(SessionHolder resourceHolder) {
			// return !resourceHolder.getSession().isClosed();
			return false;
		}
	}
}
