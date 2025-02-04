/*******************************************************************************
 * Copyright (c) 2020, 2023 Obeo.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 * 
 * Contributors:
 *     Obeo - initial API and implementation
 *******************************************************************************/
package org.eclipse.acceleo.aql.ls.services.textdocument;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.acceleo.Import;
import org.eclipse.acceleo.Metamodel;
import org.eclipse.acceleo.Module;
import org.eclipse.acceleo.ModuleReference;
import org.eclipse.acceleo.Variable;
import org.eclipse.acceleo.aql.AcceleoUtil;
import org.eclipse.acceleo.aql.ls.AcceleoLanguageServer;
import org.eclipse.acceleo.aql.ls.common.AcceleoLanguageServerPositionUtils;
import org.eclipse.acceleo.aql.ls.common.LocationUtils;
import org.eclipse.acceleo.aql.ls.services.workspace.AcceleoProject;
import org.eclipse.acceleo.aql.ls.services.workspace.AcceleoWorkspace;
import org.eclipse.acceleo.aql.parser.AcceleoAstResult;
import org.eclipse.acceleo.aql.parser.AcceleoParser;
import org.eclipse.acceleo.aql.validation.AcceleoValidator;
import org.eclipse.acceleo.aql.validation.DeclarationSwitch;
import org.eclipse.acceleo.aql.validation.IAcceleoValidationResult;
import org.eclipse.acceleo.aql.validation.quickfixes.AcceleoQuickFixesSwitch;
import org.eclipse.acceleo.query.AQLUtils;
import org.eclipse.acceleo.query.ast.ASTNode;
import org.eclipse.acceleo.query.ast.Call;
import org.eclipse.acceleo.query.ast.Declaration;
import org.eclipse.acceleo.query.ast.Expression;
import org.eclipse.acceleo.query.ast.StringLiteral;
import org.eclipse.acceleo.query.ast.VarRef;
import org.eclipse.acceleo.query.parser.AstBuilderListener;
import org.eclipse.acceleo.query.parser.quickfixes.CreateResource;
import org.eclipse.acceleo.query.parser.quickfixes.DeleteResource;
import org.eclipse.acceleo.query.parser.quickfixes.IAstQuickFix;
import org.eclipse.acceleo.query.parser.quickfixes.IAstResourceChange;
import org.eclipse.acceleo.query.parser.quickfixes.IAstTextReplacement;
import org.eclipse.acceleo.query.parser.quickfixes.MoveResource;
import org.eclipse.acceleo.query.runtime.IService;
import org.eclipse.acceleo.query.runtime.namespace.IQualifiedNameQueryEnvironment;
import org.eclipse.acceleo.query.runtime.namespace.IQualifiedNameResolver;
import org.eclipse.acceleo.query.runtime.namespace.ISourceLocation;
import org.eclipse.acceleo.query.validation.type.IType;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.DeleteFile;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;

/**
 * Represents an Acceleo Text Document as known by the Language Server. It is maintained consistent with the
 * contents of the client's editor thanks to the LSP-compliant notifications sent to the Language Server by
 * the client. It is used to provide the language services back to the client.
 * 
 * @author Florent Latombe
 */
public class AcceleoTextDocument {

	/**
	 * FIXME: move somewhere else.
	 */
	private static final String VALIDATION_NAMESPACE = "_reserved_::to::validate";

	/**
	 * The {@link URI} that uniquely identifies this document.
	 */
	private final URI uri;

	/**
	 * The {@link AcceleoProject} that contains this text document.
	 */
	private AcceleoProject ownerProject;

	/**
	 * The {@link IQualifiedNameQueryEnvironment} for this text document.
	 */
	private IQualifiedNameQueryEnvironment queryEnvironment;

	/**
	 * The {@link ResourceSet} for models.
	 */
	private ResourceSet resourceSetForModels;

	/**
	 * The {@link String} contents of the text document.
	 */
	private String contents;

	// Cached values that depend on the contents.
	/**
	 * The cached {@link AcceleoAstResult} resulting form parsing the contents of the text document.
	 */
	private AcceleoAstResult acceleoAstResult;

	/**
	 * The cached {@link IAcceleoValidationResult}, updated upon any changes in the contents of this document.
	 */
	private IAcceleoValidationResult acceleoValidationResult;

	/**
	 * The module qualified name.
	 */
	private String qualifiedName;

	/**
	 * Tells if the document is
	 * {@link TextDocumentService#didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams) opened}.
	 */
	private boolean isOpened;

	/**
	 * Creates a new {@link AcceleoTextDocument} corresponding to the given URI and with the given initial
	 * contents.
	 * 
	 * @param textDocumentUri
	 *            the (non-{@code null}) {@link URI} of this text document.
	 * @param textDocumentContents
	 *            the (non-{@code null}) initial contents of this text document.
	 * @param project
	 *            the owner project
	 */
	public AcceleoTextDocument(URI textDocumentUri, String textDocumentContents, AcceleoProject project) {
		Objects.requireNonNull(textDocumentUri);
		Objects.requireNonNull(textDocumentContents);
		this.ownerProject = project;
		this.uri = textDocumentUri;
		qualifiedName = getProject().getResolver().getQualifiedName(getUri());

		this.setContents(textDocumentContents);
	}

	/**
	 * Behavior triggered when the contents or environment of this document have changed. We update the cached
	 * values of the parsing and validation results.
	 */
	private void documentChanged() {
		// Retrieve the parsing result first, as other services depend on it.
		this.parseContents();
		getProject().getResolver().register(getModuleQualifiedName(), getAcceleoAstResult().getModule());

		// And validation second, as some other services depend on it.
		this.validateAndPublishResults();
	}

	/**
	 * This is called when this document is saved. Notifies external parties which may rely on this document.
	 */
	public void documentSaved() {
		// TODO: we probably only want to send this notification out when the "publicly-accessible" parts of
		// the Module have changed, like a public/protected Template/Query signature.
		if (this.getProject() != null) {
			// This text document belongs to an AcceleoProject which holds the resolver in which
			// this module is registered.
			this.getProject().documentSaved(this);
		}
	}

	/**
	 * Sets the owner {@link AcceleoProject} of this document.
	 * 
	 * @param acceleoProject
	 *            the new (maybe-{@code null}) owner {@link AcceleoProject}.
	 */
	public void setProject(AcceleoProject acceleoProject) {
		AcceleoProject oldProject = ownerProject;
		this.ownerProject = acceleoProject;
		if (getProject() != null) {
			qualifiedName = getProject().getResolver().getQualifiedName(getUri());
			if ((acceleoProject == null && oldProject != null) || !acceleoProject.equals(oldProject)) {
				// When the project changes, the environment changes.
				this.resolverChanged();
			}
		}
	}

	/**
	 * This is called to notify this {@link AcceleoTextDocument} that its contextual
	 * {@link IQualifiedNameResolver} has changed. As a result, it needs to be re-parsed and re-validated.
	 */
	public void resolverChanged() {
		this.documentChanged();
	}

	/**
	 * Provides the owner {@link AcceleoProject} of this document.
	 * 
	 * @return the (maybe-{@code null}) owner {@link AcceleoProject}.
	 */
	public AcceleoProject getProject() {
		return this.ownerProject;
	}

	/**
	 * Provides the file name without its extension.
	 * 
	 * @return the file name of this text document.
	 */
	public String getFileNameWithoutExtension() {
		return this.uri.toString().substring(this.uri.toString().lastIndexOf('/'), this.uri.toString()
				.lastIndexOf('.'));
	}

	/**
	 * Parses the contents of this document and updates the cached AST.
	 */
	private void parseContents() {
		AcceleoAstResult parsingResult = null;
		parsingResult = doParsing(this.getModuleQualifiedName(), this.contents);

		dispose();

		final IQualifiedNameResolver resolver = getProject().getResolver();
		// TODO get options form ??? or list all possible options ?
		// don't add any options ?
		final Map<String, String> options = new LinkedHashMap<>();
		final ArrayList<Exception> exceptions = new ArrayList<>();
		// the ResourceSet will not be used
		resourceSetForModels = AQLUtils.createResourceSetForModels(exceptions, this, new ResourceSetImpl(),
				options);
		// TODO report exceptions

		queryEnvironment = AcceleoUtil.newAcceleoQueryEnvironment(options, resolver, resourceSetForModels);

		for (Metamodel metamodel : parsingResult.getModule().getMetamodels()) {
			if (metamodel.getReferencedPackage() != null) {
				queryEnvironment.registerEPackage(metamodel.getReferencedPackage());
			}
		}
		this.acceleoAstResult = parsingResult;
	}

	public void dispose() {
		// clean any existing environment
		if (queryEnvironment != null && resourceSetForModels != null) {
			AQLUtils.cleanResourceSetForModels(this, resourceSetForModels);
			AcceleoUtil.cleanServices(queryEnvironment, resourceSetForModels);
		}

	}

	/**
	 * Performs the parsing.
	 * 
	 * @param moduleQualifiedName
	 *            the (non-{@code null}) qualified name of the document we are parsing.
	 * @param documentContents
	 *            the (non-{@code null}) contents of the document we are parsing.
	 * @return the resulting {@link AcceleoAstResult}.
	 */
	private AcceleoAstResult doParsing(String moduleQualifiedName, String documentContents) {
		final AcceleoAstResult res;

		if (isOpened()) {
			Objects.requireNonNull(moduleQualifiedName);
			Objects.requireNonNull(documentContents);
			AcceleoParser acceleoParser = new AcceleoParser();
			res = acceleoParser.parse(documentContents, moduleQualifiedName);
		} else {
			final Module module = (Module)ownerProject.getResolver().resolve(moduleQualifiedName);
			if (module == null) {
				Objects.requireNonNull(moduleQualifiedName);
				Objects.requireNonNull(documentContents);
				AcceleoParser acceleoParser = new AcceleoParser();
				res = acceleoParser.parse(documentContents, moduleQualifiedName);
				ownerProject.getResolver().register(moduleQualifiedName, res);
			} else {
				res = module.getAst();
			}
		}

		return res;
	}

	/**
	 * Validates the contents of this document, and caches its results.
	 */
	private void validateContents() {
		IAcceleoValidationResult validationResults = null;
		if (this.acceleoAstResult != null && this.getQueryEnvironment() != null) {
			validationResults = doValidation(this.acceleoAstResult, this.getQueryEnvironment(),
					getModuleQualifiedName());
		}
		this.acceleoValidationResult = validationResults;
	}

	/**
	 * Validates the contents of this document using the {@link AcceleoValidator}. As a side effect, it
	 * registers the resulting
	 * 
	 * @param acceleoAstResult
	 *            the (non-{@code null}) {@link AcceleoAstResult} to validate.
	 * @param queryEnvironment
	 *            the (non-{@code null}) {@link IQualifiedNameQueryEnvironment} of the document being
	 *            validated.
	 * @param qualifiedName
	 *            the context qualified name
	 * @return the {@link IAcceleoValidationResult}.
	 */
	private static IAcceleoValidationResult doValidation(AcceleoAstResult acceleoAstResult,
			IQualifiedNameQueryEnvironment queryEnvironment, String qualifiedName) {
		Objects.requireNonNull(acceleoAstResult);
		Objects.requireNonNull(queryEnvironment);

		return validate(queryEnvironment, acceleoAstResult, qualifiedName);
	}

	/**
	 * Provides the owning {@link AcceleoLanguageServer}.
	 * 
	 * @return the (maybe-{@code null}) owning {@link AcceleoLanguageServer}.
	 */
	public AcceleoLanguageServer getLanguageServer() {
		if (this.getProject() != null) {
			return this.getProject().getLanguageServer();
		} else {
			return null;
		}
	}

	/**
	 * Provides the {@link AcceleoTextDocumentService} of the owning {@link AcceleoLanguageServer}.
	 * 
	 * @return the (maybe-{@code null}) {@link AcceleoTextDocumentService}.
	 */
	public AcceleoTextDocumentService getTextDocumentService() {
		AcceleoLanguageServer languageServer = this.getLanguageServer();
		if (languageServer != null) {
			return languageServer.getTextDocumentService();
		} else {
			return null;
		}
	}

	/**
	 * Validates this document and publishes the validation results.
	 */
	public void validateAndPublishResults() {
		final AcceleoTextDocumentService service = this.getTextDocumentService();
		if (service != null) {
			this.validateContents();
			service.publishValidationResults(this);
		}
	}

	/**
	 * Provides the qualified name of the Acceleo {@link Module} represented by this document, as computed by
	 * the {@link IQualifiedNameResolver}.
	 * 
	 * @return the qualified name of the {@link Module}. {@code null} if this document has no
	 *         {@link IQualifiedNameResolver}.
	 */
	public String getModuleQualifiedName() {
		return qualifiedName;
	}

	/**
	 * Provides the links from the given position to the location(s) defining the element at the given
	 * position.
	 * 
	 * @param position
	 *            the (positive) position in the source contents.
	 * @return the {@link List} of {@link LocationLink} corresponding to the definition location(s) of the
	 *         Acceleo element found at the given position in the source contents.
	 */
	public List<LocationLink> getDefinitionLocations(int position) {
		// In the case of Acceleo and AQL all definition are located at the declaration.
		return getDeclarationLocations(position, false);
	}

	/**
	 * Provides the links from the given position to the location(s) declaring the element at the given
	 * position.
	 * 
	 * @param position
	 *            the (positive) position in the source contents.
	 * @param withCompatibleServices
	 *            tells if we should also return all compatible {@link IService}
	 * @return the {@link List} of {@link LocationLink} corresponding to the declaration location(s) of the
	 *         Acceleo element found at the given position in the source contents.
	 */
	public List<LocationLink> getDeclarationLocations(int position, boolean withCompatibleServices) {
		final List<LocationLink> declarationLocations = new ArrayList<>();

		final ASTNode acceleoOrAqlNodeUnderCursor = acceleoAstResult.getAstNode(position);
		final Range originSelectionRange;
		if (acceleoOrAqlNodeUnderCursor instanceof VarRef) {
			originSelectionRange = LocationUtils.identifierRange(acceleoValidationResult,
					(VarRef)acceleoOrAqlNodeUnderCursor);
		} else if (acceleoOrAqlNodeUnderCursor instanceof Call) {
			originSelectionRange = LocationUtils.identifierRange(acceleoValidationResult,
					(Call)acceleoOrAqlNodeUnderCursor);
		} else if (acceleoOrAqlNodeUnderCursor instanceof ModuleReference) {
			originSelectionRange = LocationUtils.range(acceleoValidationResult,
					(ModuleReference)acceleoOrAqlNodeUnderCursor);
		} else {
			originSelectionRange = null;
		}

		final IQualifiedNameResolver resolver = queryEnvironment.getLookupEngine().getResolver();
		final List<Object> declarations = getDeclaration(acceleoOrAqlNodeUnderCursor, withCompatibleServices);

		for (Object declaration : declarations) {
			if (declaration instanceof Declaration) {
				final Declaration aqlDeclaration = (Declaration)declaration;
				declarationLocations.add(getDeclarationLocation(originSelectionRange, aqlDeclaration));
			} else if (declaration instanceof Variable) {
				final Variable variable = (Variable)declaration;
				declarationLocations.add(getDeclarationLocation(originSelectionRange, variable));
			} else if (declaration instanceof IService<?>) {
				final IService<?> service = (IService<?>)declaration;
				final ISourceLocation sourceLocation = resolver.getSourceLocation(service);
				if (sourceLocation != null) {
					final LocationLink locationLink = new LocationLink(getUri().toASCIIString(), LocationUtils
							.range(sourceLocation), LocationUtils.identifierRange(sourceLocation));
					locationLink.setOriginSelectionRange(originSelectionRange);
					locationLink.setTargetUri(sourceLocation.getSourceURI().toASCIIString());
					declarationLocations.add(locationLink);
				}
			} else {
				final String declarationQualifiedName;
				if (declaration instanceof Module) {
					// TODO this case could fall under the last else if we didn't use VALIDATION_NAMESPACE
					declarationQualifiedName = ((Module)declaration).eResource().getURI().toString()
							.substring(AcceleoParser.ACCELEOENV_URI_PROTOCOL.length());
				} else {
					declarationQualifiedName = resolver.getQualifiedName(declaration);
				}
				final ISourceLocation sourceLocation = resolver.getSourceLocation(declarationQualifiedName);
				final LocationLink locationLink = new LocationLink(getUri().toASCIIString(), LocationUtils
						.range(sourceLocation), LocationUtils.identifierRange(sourceLocation));
				locationLink.setOriginSelectionRange(originSelectionRange);
				locationLink.setTargetUri(sourceLocation.getSourceURI().toASCIIString());
				declarationLocations.add(locationLink);
			}
		}

		return declarationLocations;
	}

	/**
	 * Gets the declaration {@link LocationLink} for the given {@link Variable}.
	 * 
	 * @param originSelectionRange
	 *            the original selection {@link Range}
	 * @param variable
	 *            the {@link Variable}
	 * @return the declaration {@link LocationLink} for the given {@link Variable}
	 */
	private LocationLink getDeclarationLocation(final Range originSelectionRange, final Variable variable) {
		final Range identifierRange = LocationUtils.identifierRange(acceleoValidationResult, variable);
		final Range range = LocationUtils.range(acceleoValidationResult, variable);
		final LocationLink locationLink = new LocationLink(getUri().toASCIIString(), range, identifierRange);
		locationLink.setOriginSelectionRange(originSelectionRange);
		locationLink.setTargetUri(getUri().toASCIIString());
		return locationLink;
	}

	/**
	 * Gets the declaration {@link LocationLink} for the given {@link Declaration}.
	 * 
	 * @param originSelectionRange
	 *            the original selection {@link Range}
	 * @param declaration
	 *            the {@link Declaration}
	 * @return the declaration {@link LocationLink} for the given {@link Declaration}
	 */
	private LocationLink getDeclarationLocation(final Range originSelectionRange, Declaration declaration) {
		final Range identifierRange = LocationUtils.identifierRange(acceleoValidationResult, declaration);
		final Range range = LocationUtils.range(acceleoValidationResult, declaration);
		final LocationLink locationLink = new LocationLink(getUri().toASCIIString(), range, identifierRange);
		locationLink.setOriginSelectionRange(originSelectionRange);
		locationLink.setTargetUri(getUri().toASCIIString());
		return locationLink;
	}

	/**
	 * Gets the {@link List} of declaration {@link Object} for the given {@link EObject}.
	 * 
	 * @param eObject
	 *            the {@link EObject}
	 * @param withCompatibleServices
	 *            tells if we should also return all compatible {@link IService}
	 * @return the {@link List} of declaration {@link Object} for the given {@link EObject}
	 */
	private List<Object> getDeclaration(EObject eObject, boolean withCompatibleServices) {
		final DeclarationSwitch declarationSwitch = new DeclarationSwitch(acceleoValidationResult,
				queryEnvironment, withCompatibleServices);

		return declarationSwitch.getDeclarations(getModuleQualifiedName(), eObject);
	}

	/**
	 * Provides the links from the location(s) defining the element at the given position to its references.
	 * 
	 * @param position
	 *            the (positive) position in the source contents.
	 * @param withCompatibleServices
	 *            tells if we should also return all compatible {@link IService}
	 * @return the {@link List} of {@link Location} corresponding to the references to the definition
	 *         location(s) of the Acceleo element found at the given position in the source contents.
	 */
	public List<Location> getReferencesLocations(int position, boolean withCompatibleServices) {
		final List<Location> referencesLocations = new ArrayList<>();

		final ASTNode acceleoOrAqlNodeUnderCursor = acceleoAstResult.getAstNode(position);
		final List<Object> declarations = getDeclaration(acceleoOrAqlNodeUnderCursor, withCompatibleServices);
		for (Object declaration : declarations) {
			if (declaration instanceof Declaration) {
				for (VarRef varRef : acceleoValidationResult.getResolvedVarRef((Declaration)declaration)) {
					final Location location = LocationUtils.identifierLocation(queryEnvironment,
							getModuleQualifiedName(), acceleoValidationResult, varRef);
					referencesLocations.add(location);
				}
			} else if (declaration instanceof Variable) {
				for (VarRef varRef : acceleoValidationResult.getResolvedVarRef((Variable)declaration)) {
					final Location location = LocationUtils.identifierLocation(queryEnvironment,
							getModuleQualifiedName(), acceleoValidationResult, varRef);
					referencesLocations.add(location);
				}
			} else if (declaration instanceof IService<?>) {
				final IService<?> service = (IService<?>)declaration;
				referencesLocations.addAll(getServiceReferenceLocations(acceleoOrAqlNodeUnderCursor,
						service));
			} else {
				final IQualifiedNameResolver resolver = queryEnvironment.getLookupEngine().getResolver();
				final AcceleoWorkspace workspace = getProject().getWorkspace();
				final String declarationQualifiedName;
				if (declaration instanceof Module) {
					// TODO this case could fall under the last else if we didn't use VALIDATION_NAMESPACE
					declarationQualifiedName = ((Module)declaration).eResource().getURI().toString()
							.substring(AcceleoParser.ACCELEOENV_URI_PROTOCOL.length());
				} else {
					declarationQualifiedName = resolver.getQualifiedName(declaration);
				}
				if (declarationQualifiedName != null) {
					for (String dependentQualifiedName : resolver.getDependOn(declarationQualifiedName)) {
						final URI sourceURI = resolver.getSourceURI(dependentQualifiedName);
						final AcceleoTextDocument document = workspace.getTextDocument(sourceURI);
						if (document != null) {
							final Module module = document.getAcceleoAstResult().getModule();
							if (module.getExtends() != null && declarationQualifiedName.equals(module
									.getExtends().getQualifiedName())) {
								final Location location = LocationUtils.location(queryEnvironment,
										dependentQualifiedName, document.getAcceleoValidationResults(), module
												.getExtends());
								referencesLocations.add(location);
							} else {
								for (Import imported : module.getImports()) {
									if (declarationQualifiedName.equals(imported.getModule()
											.getQualifiedName())) {
										final Location location = LocationUtils.location(queryEnvironment,
												dependentQualifiedName, document
														.getAcceleoValidationResults(), imported.getModule());
										referencesLocations.add(location);
									}
								}
							}
						} else {
							// TODO not a module but a class or something else
						}
					}
				}
			}
		}

		return referencesLocations;
	}

	/**
	 * Gets the {@link List} of reference {@link Location} for the given {@link IService} declaration.
	 * 
	 * @param acceleoOrAqlNodeUnderCursor
	 *            the {@link ASTNode} at the cursor position
	 * @param service
	 *            the {@link IService} declaration
	 * @return the {@link List} of reference {@link Location} for the given {@link IService} declaration
	 */
	private final List<Location> getServiceReferenceLocations(ASTNode acceleoOrAqlNodeUnderCursor,
			IService<?> service) {
		final List<Location> res = new ArrayList<>();

		final IQualifiedNameResolver resolver = queryEnvironment.getLookupEngine().getResolver();
		final AcceleoWorkspace workspace = getProject().getWorkspace();
		final Set<String> qualifiedNames = new LinkedHashSet<>();
		String serviceContextQualifiedName = resolver.getContextQualifiedName(service);

		// aqlFeatureAccess
		final Set<IType> featuresPossibleTypes;
		final String featureName;
		if (AstBuilderListener.FEATURE_ACCESS_SERVICE_NAME.equals(service.getName())) {
			final Call originalCall = (Call)acceleoOrAqlNodeUnderCursor.eContainer();
			final Expression receiver = originalCall.getArguments().get(0);
			featuresPossibleTypes = acceleoValidationResult.getPossibleTypes(receiver);
			featureName = ((StringLiteral)originalCall.getArguments().get(1)).getValue();
		} else {
			featuresPossibleTypes = null;
			featureName = null;
		}

		if (serviceContextQualifiedName != null) {
			if (serviceContextQualifiedName.startsWith(VALIDATION_NAMESPACE
					+ AcceleoParser.QUALIFIER_SEPARATOR)) {
				serviceContextQualifiedName = serviceContextQualifiedName.substring((VALIDATION_NAMESPACE
						+ AcceleoParser.QUALIFIER_SEPARATOR).length());

			}
			qualifiedNames.add(serviceContextQualifiedName);
			qualifiedNames.addAll(resolver.getDependOn(serviceContextQualifiedName));
		} else {
			// probably a standard service (select, oclIsKindOf, ...)
			// we need to scan all modules
			qualifiedNames.addAll(workspace.getAllTextDocuments().stream().map(d -> d
					.getModuleQualifiedName()).collect(Collectors.toList()));
		}
		for (String dependentQualifiedName : qualifiedNames) {
			final URI sourceURI = resolver.getSourceURI(dependentQualifiedName);
			final AcceleoTextDocument document = workspace.getTextDocument(sourceURI);
			if (document != null) {
				res.addAll(document.getLocalCallLocations(featuresPossibleTypes, featureName, service));
			} else {
				// TODO not a module but a class or something else
			}
		}

		return res;
	}

	/**
	 * Tells if the given {@link Call} is compatible with the given {@link Set} of possible {@link IType} and
	 * the given feature name.
	 * 
	 * @param featuresPossibleTypes
	 *            the {@link Set} of possible {@link IType}
	 * @param featureName
	 *            the feature name
	 * @param call
	 *            the {@link Call} to check
	 * @return <code>true</code> if the given {@link Call} is compatible with the given {@link Set} of
	 *         possible {@link IType} and the given feature name, <code>false</code> otherwise
	 */
	private boolean isCompatibleAqlFeatureAccess(Set<IType> featuresPossibleTypes, String featureName,
			Call call) {
		boolean res = false;
		final Expression receiver = call.getArguments().get(0);
		final Set<IType> receiverTypes = acceleoValidationResult.getPossibleTypes(receiver);
		if (call.getArguments().size() == 2 && featureName.equals(((StringLiteral)call.getArguments().get(1))
				.getValue())) {
			compatible: for (IType featuresPossibleType : featuresPossibleTypes) {
				for (IType receiverType : receiverTypes) {
					if (featuresPossibleType.isAssignableFrom(receiverType)) {
						res = true;
						break compatible;
					}
				}
			}
		}
		return res;
	}

	/**
	 * Gets the local {@link Location} of {@link Call} resolved to the given {@link IService}.
	 * 
	 * @param featuresPossibleTypes
	 *            the {@link Set} of possible {@link IType} in the case of an aql feature access,
	 *            <code>null</code> otherwise
	 * @param featureName
	 *            the feature name in the case of an aql feature access, <code>null</code> otherwise
	 * @param service
	 *            the {@link IService}
	 * @return the local {@link Location} of {@link Call} resolved to the given {@link IService}
	 */
	public List<Location> getLocalCallLocations(Set<IType> featuresPossibleTypes, String featureName,
			IService<?> service) {
		final List<Location> referencesLocations = new ArrayList<>();

		final List<Call> resolvedCalls = acceleoValidationResult.getResolvedCalls(service);
		for (Call call : resolvedCalls) {
			if (featureName != null && featuresPossibleTypes != null) {
				if (isCompatibleAqlFeatureAccess(featuresPossibleTypes, featureName, call)) {
					final Location location = LocationUtils.identifierLocation(queryEnvironment,
							getModuleQualifiedName(), acceleoValidationResult, call);
					referencesLocations.add(location);
				} else {
					// not compatible aql feature access
				}
			} else {
				final Location location = LocationUtils.identifierLocation(queryEnvironment,
						getModuleQualifiedName(), acceleoValidationResult, call);
				referencesLocations.add(location);
			}
		}

		return referencesLocations;
	}

	/**
	 * Provides the URI of this {@link AcceleoTextDocument}.
	 * 
	 * @return the {@link URI} of this {@link AcceleoTextDocument}.
	 */
	public URI getUri() {
		return this.uri;
	}

	/**
	 * Applies {@link TextDocumentContentChangeEvent TextDocumentContentChangeEvents} to the contents of this
	 * text document.
	 * 
	 * @param textDocumentContentchangeEvents
	 *            the {@link Iterable} of {@link TextDocumentContentChangeEvent} to apply, in the same order.
	 * @return this {@link AcceleoTextDocument}, with the new contents resulting from applying the changes.
	 */
	public AcceleoTextDocument applyChanges(
			Iterable<TextDocumentContentChangeEvent> textDocumentContentchangeEvents) {
		String newTextDocumentContents = this.contents;
		for (TextDocumentContentChangeEvent textDocumentContentChangeEvent : textDocumentContentchangeEvents) {
			newTextDocumentContents = apply(textDocumentContentChangeEvent, newTextDocumentContents);
		}
		this.setContents(newTextDocumentContents);

		return this;
	}

	/**
	 * Updates the known contents of the text document, which triggers the parsing of the new contents.
	 * 
	 * @param newTextDocumentContents
	 *            the new (non-{@code null}) contents of the text document.
	 */
	public void setContents(String newTextDocumentContents) {
		this.contents = newTextDocumentContents;
		this.documentChanged();
	}

	/**
	 * Provides the Abstract Syntax Tree (AST) corresponding to the contents of this text document.
	 * 
	 * @return the (non-{@code null}) {@link AcceleoAstResult} of this document.
	 */
	public AcceleoAstResult getAcceleoAstResult() {
		return acceleoAstResult;
	}

	/**
	 * Provides the results of the Acceleo validation of the contents of this text document.
	 * 
	 * @return the (non-{@code null}) {@link IAcceleoValidationResult} of this document.
	 */
	public IAcceleoValidationResult getAcceleoValidationResults() {
		return this.acceleoValidationResult;
	}

	/**
	 * Provides the {@link IQualifiedNameQueryEnvironment} associated to this text document.
	 * 
	 * @return the (maybe-{@code null}) {@link IQualifiedNameQueryEnvironment} associated to this
	 *         {@link AcceleoTextDocument}.
	 */
	public IQualifiedNameQueryEnvironment getQueryEnvironment() {
		return queryEnvironment;
	}

	/**
	 * Provides the current {@link String text contents} of this text document.
	 * 
	 * @return the (non-{@code null}) {@link String} contents of this {@link AcceleoTextDocument}.
	 */
	public String getContents() {
		return this.contents;
	}

	/**
	 * Performs the Acceleo validation.
	 * 
	 * @param queryEnvironment
	 *            the (non-{@code null}) {@link IQualifiedNameQueryEnvironment}.
	 * @param acceleoAstResult
	 *            the (non-{@code null}) {@link AcceleoAstResult}.
	 * @param qualifiedName
	 *            the context qualified name
	 * @return the {@link IAcceleoValidationResult}.
	 */
	// FIXME the "synchronized" here is an ugly but convenient way to ensure that a validation finishes before
	// any other is triggered. Otherwise a validation can push imports which invalidates the services lookup
	// for another validation
	private static synchronized IAcceleoValidationResult validate(
			IQualifiedNameQueryEnvironment queryEnvironment, AcceleoAstResult acceleoAstResult,
			String qualifiedName) {
		String moduleQualifiedNameForValidation = VALIDATION_NAMESPACE + AcceleoParser.QUALIFIER_SEPARATOR
				+ qualifiedName;
		final IQualifiedNameResolver resolver = queryEnvironment.getLookupEngine().getResolver();
		resolver.register(moduleQualifiedNameForValidation, acceleoAstResult.getModule());
		try {
			final AcceleoValidator acceleoValidator = new AcceleoValidator(queryEnvironment);
			final IAcceleoValidationResult validationResults = acceleoValidator.validate(acceleoAstResult,
					moduleQualifiedNameForValidation);
			return validationResults;
		} finally {
			resolver.clear(Collections.singleton(moduleQualifiedNameForValidation));
		}
	}

	/**
	 * Applies a {@link TextDocumentContentChangeEvent} to the {@link String} representing the text document
	 * contents.
	 * 
	 * @param textDocumentContentChangeEvent
	 *            the (non-{@code null}) {@link TextDocumentContentChangeEvent} to apply.
	 * @param inText
	 *            the (non-{@code null}) current {@link String text document contents}.
	 * @return the new {@link String text document contents}.
	 */
	private static String apply(TextDocumentContentChangeEvent textDocumentContentChangeEvent,
			String inText) {
		String newTextExcerpt = textDocumentContentChangeEvent.getText();
		Range changeRange = textDocumentContentChangeEvent.getRange();
		// We can safely ignore the range length, which gets deprecated in the next version of LSP.
		// cf. https://github.com/Microsoft/language-server-protocol/issues/9

		if (changeRange == null) {
			// The whole text was replaced.
			return newTextExcerpt;
		} else {
			return AcceleoLanguageServerPositionUtils.replace(inText, changeRange.getStart(), changeRange
					.getEnd(), newTextExcerpt);
		}
	}

	/**
	 * Tells if the document is
	 * {@link TextDocumentService#didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams) opened}.
	 * 
	 * @return <code>true</code> if the document is
	 *         {@link TextDocumentService#didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams) opened},
	 *         <code>false</code> otherwise
	 */
	public boolean isOpened() {
		return isOpened;
	}

	/**
	 * Sets if the document is {@link TextDocumentService#didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams)
	 * opened}.
	 * 
	 * @param opened
	 *            the new value
	 */
	public void setOpened(boolean opened) {
		this.isOpened = opened;
	}

	/**
	 * Gets the {@link PrepareRenameResult} for the given index.
	 * 
	 * @param position
	 *            the (positive) position in the source contents.
	 * @return the {@link PrepareRenameResult} for the given index if any, <code>null</code> otherwise
	 */
	public PrepareRenameResult getPrepareRenameResult(int position) {
		final PrepareRenameResult res;

		final ASTNode astNode = acceleoAstResult.getAstNode(position);
		if (astNode != null) {
			final int start = acceleoAstResult.getIdentifierStartPosition(astNode);
			final int end = acceleoAstResult.getIdentifierEndPosition(astNode);
			if (start <= position && position <= end) {
				final Range range = LocationUtils.identifierRange(acceleoValidationResult, astNode);
				res = new PrepareRenameResult(range, contents.substring(start, end));
			} else {
				res = null;
			}
		} else {
			res = null;
		}

		return res;
	}

	/**
	 * Gets the mapping from a {@link URI} to its {@link List} of {@link TextEdit}.
	 * 
	 * @param position
	 *            the (positive) position in the source contents.
	 * @param newName
	 *            the new name
	 * @return the mapping from a {@link URI} to its {@link List} of {@link TextEdit}
	 */
	public Map<String, List<TextEdit>> getRenames(int position, String newName) {
		final Map<String, List<TextEdit>> res = new HashMap<>();

		for (LocationLink locationLink : getDeclarationLocations(position, true)) {
			final List<TextEdit> edits = res.computeIfAbsent(locationLink.getTargetUri(),
					uri -> new ArrayList<>());
			edits.add(new TextEdit(locationLink.getTargetSelectionRange(), newName));
		}

		for (Location location : getReferencesLocations(position, true)) {
			final List<TextEdit> edits = res.computeIfAbsent(location.getUri(), uri -> new ArrayList<>());
			edits.add(new TextEdit(location.getRange(), newName));
		}

		return res;
	}

	public List<Either<Command, CodeAction>> getCodeActions(int atStartIndex, int atEndIndex,
			CodeActionContext context) {
		final List<Either<Command, CodeAction>> res;

		final ASTNode astNode = acceleoAstResult.getAstNode(atEndIndex);
		if (astNode != null) {
			final AcceleoQuickFixesSwitch quickFixesSwitch = new AcceleoQuickFixesSwitch(
					getQueryEnvironment(), acceleoValidationResult, getModuleQualifiedName(), getContents());
			final List<IAstQuickFix> quickFixes = quickFixesSwitch.getQuickFixes(astNode);
			res = quickFixes.stream().map(qf -> transform(qf)).collect(Collectors.toList());
		} else {
			res = Collections.emptyList();
		}
		return res;
	}

	private Either<Command, CodeAction> transform(IAstQuickFix quickFix) {
		final CodeAction res = new CodeAction(quickFix.getName());
		final WorkspaceEdit workspaceEdit = new WorkspaceEdit();
		res.setEdit(workspaceEdit);
		final List<Either<TextDocumentEdit, ResourceOperation>> documentChanges = new ArrayList<>();
		workspaceEdit.setDocumentChanges(documentChanges);

		for (IAstResourceChange change : quickFix.getResourceChanges()) {
			final ResourceOperation resourceOperation;
			if (change instanceof CreateResource) {
				resourceOperation = new CreateFile(((CreateResource)change).getUri().toString());
			} else if (change instanceof DeleteResource) {
				resourceOperation = new DeleteFile(((DeleteResource)change).getUri().toString());
			} else if (change instanceof MoveResource) {
				resourceOperation = new RenameFile(((MoveResource)change).getSource().toString(),
						((MoveResource)change).getTarget().toString());
			} else {
				throw new IllegalStateException("unknown resource change.");
			}
			documentChanges.add(Either.forRight(resourceOperation));
		}

		for (IAstTextReplacement replacement : quickFix.getTextReplacements()) {
			final TextEdit textEdit = new TextEdit(getRange(replacement), replacement.getReplacement());
			// TODO version
			final VersionedTextDocumentIdentifier version = new VersionedTextDocumentIdentifier(replacement
					.getURI().toString(), null);
			final TextDocumentEdit textDocumentEdit = new TextDocumentEdit(version, new ArrayList<>());
			textDocumentEdit.getEdits().add(textEdit);
			documentChanges.add(Either.forLeft(textDocumentEdit));
		}

		return Either.forRight(res);
	}

	/**
	 * Gets the {@link Range} corresponding to the given {@link IAstTextReplacement}.
	 * 
	 * @param replacement
	 *            the {@link IAstTextReplacement}
	 * @return the {@link Range} corresponding to the given {@link IAstTextReplacement}
	 */
	private Range getRange(IAstTextReplacement replacement) {
		final Position start = new Position(replacement.getStartLine(), replacement.getStartColumn());
		final Position end = new Position(replacement.getEndLine(), replacement.getEndColumn());

		return new Range(start, end);
	}

}
