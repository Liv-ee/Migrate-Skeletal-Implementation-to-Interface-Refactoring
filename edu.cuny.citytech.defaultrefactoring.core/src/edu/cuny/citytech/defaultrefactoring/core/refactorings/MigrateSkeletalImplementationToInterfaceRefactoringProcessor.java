package edu.cuny.citytech.defaultrefactoring.core.refactorings;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTRequestor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Modifier.ModifierKeyword;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.base.JavaStatusContext;
import org.eclipse.jdt.internal.corext.refactoring.changes.DynamicValidationRefactoringChange;
import org.eclipse.jdt.internal.corext.refactoring.structure.ASTNodeSearchUtil;
import org.eclipse.jdt.internal.corext.refactoring.structure.CompilationUnitRewrite;
import org.eclipse.jdt.internal.corext.refactoring.structure.HierarchyProcessor;
import org.eclipse.jdt.internal.corext.refactoring.util.RefactoringASTParser;
import org.eclipse.jdt.internal.corext.refactoring.util.TextEditBasedChangeManager;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.internal.corext.util.JdtFlags;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.ui.JavaElementLabels;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.GroupCategory;
import org.eclipse.ltk.core.refactoring.GroupCategorySet;
import org.eclipse.ltk.core.refactoring.RefactoringDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusContext;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext;
import org.eclipse.text.edits.TextEdit;
import org.osgi.framework.FrameworkUtil;

import edu.cuny.citytech.defaultrefactoring.core.descriptors.MigrateSkeletalImplementationToInterfaceRefactoringDescriptor;
import edu.cuny.citytech.defaultrefactoring.core.messages.Messages;
import edu.cuny.citytech.defaultrefactoring.core.utils.RefactoringAvailabilityTester;

// TODO: Are we checking the target interface? I think that the target interface should be completely empty for now.

/**
 * The activator class controls the plug-in life cycle
 * 
 * @author <a href="mailto:rkhatchadourian@citytech.cuny.edu">Raffi
 *         Khatchadourian</a>
 */
@SuppressWarnings({ "restriction" })
public class MigrateSkeletalImplementationToInterfaceRefactoringProcessor extends HierarchyProcessor {

	/**
	 * The destination interface.
	 */
	private IType destinationInterface;

	private Map<CompilationUnit, ASTRewrite> compilationUnitToASTRewriteMap = new HashMap<>();

	private Map<ITypeRoot, CompilationUnit> typeRootToCompilationUnitMap = new HashMap<>();

	@SuppressWarnings("unused")
	private static final GroupCategorySet SET_MIGRATE_METHOD_IMPLEMENTATION_TO_INTERFACE = new GroupCategorySet(
			new GroupCategory("edu.cuny.citytech.defaultrefactoring", //$NON-NLS-1$
					Messages.MigrateSkeletalImplementationToInferfaceRefactoring_CategoryName,
					Messages.MigrateSkeletalImplementationToInferfaceRefactoring_CategoryDescription));

	/**
	 * Creates a new refactoring with the given methods to refactor.
	 * 
	 * @param methods
	 *            The methods to refactor.
	 * @throws JavaModelException
	 */
	public MigrateSkeletalImplementationToInterfaceRefactoringProcessor(final IMethod[] methods,
			final CodeGenerationSettings settings, boolean layer, IProgressMonitor monitor) throws JavaModelException {
		super(methods, settings, layer);

		if (methods != null && methods.length > 0) {
			IType[] candidateTypes = this.getCandidateTypes(monitor);

			if (candidateTypes != null && candidateTypes.length > 0) {
				// TODO: For now, #23.
				if (candidateTypes.length > 1)
					logWarning("Encountered multiple candidate types (" + candidateTypes.length + ").");

				this.setDestinationType(candidateTypes[0]);
			}
		}
	}

	public MigrateSkeletalImplementationToInterfaceRefactoringProcessor(final IMethod[] methods,
			final CodeGenerationSettings settings, IProgressMonitor monitor) throws JavaModelException {
		this(methods, settings, false, monitor);
	}

	public MigrateSkeletalImplementationToInterfaceRefactoringProcessor(IProgressMonitor monitor)
			throws JavaModelException {
		this(null, null, false, monitor);
	}

	public MigrateSkeletalImplementationToInterfaceRefactoringProcessor() throws JavaModelException {
		this(null, null, false, new NullProgressMonitor());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object[] getElements() {
		return fMembersToMove;
	}

	@Override
	public RefactoringStatus checkInitialConditions(IProgressMonitor pm)
			throws CoreException, OperationCanceledException {
		try {
			pm.beginTask(Messages.MigrateSkeletalImplementationToInferfaceRefactoring_CheckingPreconditions, 1);

			if (this.fMembersToMove.length == 0)
				return RefactoringStatus.createFatalErrorStatus(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_MethodsNotSpecified);
			else if (this.fMembersToMove.length > 1) {
				// TODO: For now.
				return RefactoringStatus.createFatalErrorStatus(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMoreThanOneMethod);
			} else {
				final RefactoringStatus status = new RefactoringStatus();
				status.merge(checkDeclaringType(new SubProgressMonitor(pm, 1)));

				if (status.hasFatalError())
					return status;

				status.merge(checkIfMembersExist());
				return status;
			}

		} catch (Exception e) {
			JavaPlugin.log(e);
			throw e;
		} finally {
			pm.done();
		}
	}

	protected RefactoringStatus checkDestinationInterfaceMethods(IProgressMonitor monitor) throws JavaModelException {
		final IType targetInterface = this.getDestinationInterface();
		Assert.isNotNull(targetInterface);

		RefactoringStatus status = new RefactoringStatus();

		// TODO: For now, the target interface must only contain the target
		// method.
		List<IMethod> methodsToMoveList = Arrays.asList(this.getMethodsToMove());
		Set<IMethod> methodsToMoveSet = new HashSet<>(methodsToMoveList);

		List<IMethod> destinationInterfaceMethodsList = Arrays.asList(targetInterface.getMethods());
		Set<IMethod> destinationInterfaceMethodsSet = new HashSet<>(destinationInterfaceMethodsList);

		// ensure that the methods to move are the same as the ones in the
		// interface.
		boolean equals;

		// if they are different sizes, they can't be the same.
		if (methodsToMoveSet.size() != destinationInterfaceMethodsSet.size())
			equals = false;
		else
			// make sure there's a match for each method. 
			equals = methodsToMoveSet.parallelStream().map(targetInterface::findMethods).map(Optional::ofNullable)
					.map(o -> o.map(a -> a.length)).mapToInt(o -> o.orElse(0)).allMatch(l -> l == 1);

		if (!equals)
			addWarning(status,
					Messages.MigrateSkeletalImplementationToInferfaceRefactoring_DestinationInterfaceMustOnlyDeclareTheMethodToMigrate,
					destinationInterface);

		return status;
	}

	protected RefactoringStatus checkDestinationInterface(IProgressMonitor monitor) throws JavaModelException {
		RefactoringStatus status = new RefactoringStatus();
		// TODO #19
		final IType destinationInterface = this.getDestinationInterface();

		// Can't be null.
		if (destinationInterface == null) {
			addWarning(status, Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoDestinationInterface);
			return status;
		}

		// Must be an interface.
		if (!isPureInterface(destinationInterface))
			addWarning(status,
					Messages.MigrateSkeletalImplementationToInferfaceRefactoring_DestinationTypeMustBePureInterface,
					destinationInterface);

		status.merge(checkDestinationInterfaceMethods(new SubProgressMonitor(monitor, 1)));

		return status;
	}

	private void addWarning(RefactoringStatus status, String message) {
		addWarning(status, message, null);
	}

	@Override
	protected RefactoringStatus checkDeclaringType(IProgressMonitor monitor) throws JavaModelException {
		RefactoringStatus status = super.checkDeclaringType(monitor);

		if (!status.hasFatalError()) {
			final IType type = getDeclaringType();

			if (type.isAnonymous()) {
				// TODO for now.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInAnonymousTypes, type);
			}
			// TODO: This is being checked by the super implementation but need
			// to revisit. It might be okay to have an enum. In that case, we
			// can't call the super method.
			// if (type.isEnum()) {
			// // TODO for now.
			// addWarning(status,
			// Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInEnums,
			// method);
			// }
			if (type.isLambda()) {
				// TODO for now.
				return createFatalError(Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInLambdas,
						type);
			}
			if (type.isLocal()) {
				// TODO for now.
				return createFatalError(Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInLocals,
						type);
			}
			if (type.isMember()) {
				// TODO for now.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInMemberTypes, type);
			}
			if (!type.isClass()) {
				// TODO for now.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_MethodsOnlyInClasses, type);
			}
			if (type.getAnnotations().length != 0) {
				// TODO for now.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInAnnotatedTypes, type);
			}
			if (type.getFields().length != 0) {
				// TODO for now.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInTypesWithFields, type);
			}
			if (type.getInitializers().length != 0) {
				// TODO for now.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInTypesWithInitializers,
						type);
			}
			if (type.getMethods().length > 1) {
				// TODO for now.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInTypesWithMoreThanOneMethod,
						type);
			}
			if (type.getTypeParameters().length != 0) {
				// TODO for now.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInTypesWithTypeParameters,
						type);
			}
			if (type.getTypes().length != 0) {
				// TODO for now.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInTypesWithType, type);
			}
			if (type.getSuperclassName() != null) {
				// TODO for now.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInTypesWithSuperType,
						type);
			}
			if (type.getSuperInterfaceNames().length == 0) {
				// enclosing type must implement an interface, at least for now,
				// which one of which will become the target interface.
				// it is probably possible to still perform the refactoring
				// without this condition but I believe that this is
				// the particular pattern we are targeting.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInTypesThatDontImplementInterfaces,
						type);
			}
			if (type.getSuperInterfaceNames().length > 1) {
				// TODO for now. Let's only deal with a single interface as that
				// is part of the targeted pattern.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInTypesThatExtendMultipleInterfaces,
						type);
			}
			if (!Flags.isAbstract(type.getFlags())) {
				// TODO for now. This follows the target pattern. Maybe we can
				// relax this but that would require checking for
				// instantiations.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInConcreteTypes, type);
			}
			if (Flags.isStatic(type.getFlags())) {
				// TODO no static types for now.
				return createFatalError(
						Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInStaticTypes, type);
			}

			status.merge(checkDeclaringSuperTypes(monitor));
		}

		return status;
	}

	protected RefactoringStatus checkDeclaringSuperTypes(final IProgressMonitor monitor) throws JavaModelException {
		final RefactoringStatus result = new RefactoringStatus();
		IType[] interfaces = getCandidateTypes(monitor);

		if (interfaces.length == 0) {
			IType declaringType = getDeclaringType();

			final String msg = MessageFormat.format(
					Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInTypesWithNoCandidateTargetTypes,
					createLabel(declaringType));

			return RefactoringStatus.createWarningStatus(msg);
		} else if (interfaces.length > 1) {
			// TODO For now, let's make sure there's only one candidate type.
			IType declaringType = getDeclaringType();

			final String msg = MessageFormat.format(
					Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsInTypesWithMultipleCandidateTargetTypes,
					JavaElementLabels.getTextLabel(declaringType, JavaElementLabels.ALL_FULLY_QUALIFIED));

			return RefactoringStatus.createWarningStatus(msg);
		}

		return result;
	}

	/**
	 * Returns the possible target interfaces for the migration. NOTE: One
	 * difference here between this refactoring and pull up is that we can have
	 * a much more complex type hierarchy due to multiple interface inheritance
	 * in Java.
	 * 
	 * TODO: It should be possible to pull up a method into an interface (i.e.,
	 * "Pull Up Method To Interface") that is not implemented explicitly. For
	 * example, there may be a skeletal implementation class that implements all
	 * the target interface's methods without explicitly declaring so.
	 * 
	 * @param monitor
	 *            A progress monitor.
	 * @return The possible target interfaces for the migration.
	 * @throws JavaModelException
	 *             upon Java model problems.
	 */
	public IType[] getCandidateTypes(final IProgressMonitor monitor) throws JavaModelException {
		IType declaringType = getDeclaringType();
		IType[] superInterfaces = declaringType.newSupertypeHierarchy(monitor).getAllSuperInterfaces(declaringType);

		return Stream.of(superInterfaces).parallel()
				.filter(t -> t != null && t.exists() && !t.isReadOnly() && !t.isBinary()).toArray(IType[]::new);
	}

	protected RefactoringStatus checkMethodsToMove(IProgressMonitor pm) throws JavaModelException {
		try {
			RefactoringStatus status = new RefactoringStatus();
			Iterator<IMethod> it = getMethodsToMoveIterator();

			while (it.hasNext()) {
				IMethod method = it.next();

				if (!method.exists()) {
					addWarning(status, Messages.MigrateSkeletalImplementationToInferfaceRefactoring_MethodDoesNotExist,
							method);
				}
				if (method.isBinary() || method.isReadOnly()) {
					addWarning(status, Messages.MigrateSkeletalImplementationToInferfaceRefactoring_CantChangeMethod,
							method);
				}
				if (!method.isStructureKnown()) {
					addWarning(status,
							Messages.MigrateSkeletalImplementationToInferfaceRefactoring_CUContainsCompileErrors,
							method);
				}
				if (method.isConstructor()) {
					addWarning(status, Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoConstructors,
							method);
				}
				if (method.getAnnotations().length > 0) {
					// TODO for now.
					addWarning(status, Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoAnnotations,
							method);
				}
				if (Flags.isStatic(method.getFlags())) {
					// TODO for now.
					addWarning(status, Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoStaticMethods,
							method);
				}
				if (JdtFlags.isNative(method)) {
					addWarning(status, Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoNativeMethods,
							method);
				}
				if (method.isLambdaMethod()) {
					// TODO for now.
					addWarning(status, Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoLambdaMethods,
							method);
				}
				if (method.getExceptionTypes().length != 0) {
					// TODO for now.
					addWarning(status,
							Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsThatThrowExceptions,
							method);
				}
				if (method.getParameters().length != 0) {
					// TODO for now.
					addWarning(status,
							Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsWithParameters,
							method);
				}
				if (!method.getReturnType().equals(Signature.SIG_VOID)) {
					// return type must be void.
					// TODO for now.
					addWarning(status,
							Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsWithReturnTypes,
							method);
				}
				if (method.getTypeParameters().length != 0) {
					// TODO for now but this will be an important one.
					addWarning(status,
							Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsWithTypeParameters,
							method);
				}
				pm.worked(1);
			}

			if (!status.hasFatalError())
				status.merge(checkMethodsToMoveBodies(new SubProgressMonitor(pm, fMembersToMove.length)));

			return status;
		} finally {
			pm.done();
		}
	}

	protected Iterator<IMethod> getMethodsToMoveIterator() {
		return Stream.of(fMembersToMove).parallel().filter(m -> m instanceof IMethod).map(m -> (IMethod) m).iterator();
	}

	protected IMethod[] getMethodsToMove() {
		return Stream.of(fMembersToMove).parallel().filter(m -> m instanceof IMethod).map(m -> (IMethod) m)
				.toArray(IMethod[]::new);
	}

	protected RefactoringStatus checkMethodsToMoveBodies(IProgressMonitor pm) throws JavaModelException {
		try {
			RefactoringStatus status = new RefactoringStatus();
			Iterator<IMethod> it = this.getMethodsToMoveIterator();

			while (it.hasNext()) {
				IMethod method = it.next();
				ITypeRoot root = method.getCompilationUnit();
				CompilationUnit unit = this.getCompilationUnit(root, new SubProgressMonitor(pm, 1));

				MethodDeclaration declaration = ASTNodeSearchUtil.getMethodDeclarationNode(method, unit);

				if (declaration != null) {
					Block body = declaration.getBody();

					if (body != null) {
						@SuppressWarnings("rawtypes")
						List statements = body.statements();

						if (!statements.isEmpty()) {
							// TODO for now.
							addWarning(status,
									Messages.MigrateSkeletalImplementationToInferfaceRefactoring_NoMethodsWithStatements,
									method);
						}
					}
				}
				pm.worked(1);
			}

			return status;
		} finally {
			pm.done();
		}
	}

	private static void addWarning(RefactoringStatus status, String message, IMember member) {
		if (member != null) { // workaround
								// https://bugs.eclipse.org/bugs/show_bug.cgi?id=475753.
		String elementName = JavaElementLabels.getElementLabel(member, JavaElementLabels.ALL_FULLY_QUALIFIED);
			message = MessageFormat.format(message, elementName);
		}

		RefactoringStatusContext context = JavaStatusContext.create(member);
		status.addWarning(message, context);
	}

	private static void addError(RefactoringStatus status, String message, IMember member, IMember... more) {
		List<String> elementNames = new ArrayList<>();
		elementNames.add(JavaElementLabels.getElementLabel(member, JavaElementLabels.ALL_FULLY_QUALIFIED));

		Stream<String> stream = Arrays.asList(more).parallelStream()
				.map(MigrateSkeletalImplementationToInterfaceRefactoringProcessor::createLabel);
		Stream<String> concat = Stream.concat(elementNames.stream(), stream);
		List<String> collect = concat.collect(Collectors.toList());

		status.addError(MessageFormat.format(message, collect.toArray()), JavaStatusContext.create(member));
	}

	private static RefactoringStatus createFatalError(String message, IType type) {
		String elementName = createLabel(type);
		return RefactoringStatus.createFatalErrorStatus(MessageFormat.format(message, elementName),
				JavaStatusContext.create(type));
	}

	/**
	 * Creates a working copy layer if necessary.
	 *
	 * @param monitor
	 *            the progress monitor to use
	 * @return a status describing the outcome of the operation
	 */
	protected RefactoringStatus createWorkingCopyLayer(IProgressMonitor monitor) {
		try {
			monitor.beginTask(Messages.MigrateSkeletalImplementationToInferfaceRefactoring_CheckingPreconditions, 1);
			ICompilationUnit unit = getDeclaringType().getCompilationUnit();
			if (fLayer)
				unit = unit.findWorkingCopy(fOwner);
			resetWorkingCopies(unit);
			return new RefactoringStatus();
		} finally {
			monitor.done();
		}
	}

	@Override
	public RefactoringStatus checkFinalConditions(final IProgressMonitor monitor, final CheckConditionsContext context)
			throws CoreException, OperationCanceledException {
		try {
			monitor.beginTask(Messages.MigrateSkeletalImplementationToInferfaceRefactoring_CheckingPreconditions, 12);
			clearCaches();

			final RefactoringStatus status = new RefactoringStatus();

			// workaround https://bugs.eclipse.org/bugs/show_bug.cgi?id=474524.
			if (fMembersToMove.length > 0)
				status.merge(createWorkingCopyLayer(new SubProgressMonitor(monitor, 4)));
			if (status.hasFatalError())
				return status;
			if (monitor.isCanceled())
				throw new OperationCanceledException();

			status.merge(checkMethodsToMove(new SubProgressMonitor(monitor, 1)));
			if (status.hasFatalError())
				return status;

			status.merge(checkDestinationInterface(new SubProgressMonitor(monitor, 1)));
			if (status.hasFatalError())
				return status;

			// workaround https://bugs.eclipse.org/bugs/show_bug.cgi?id=474524.
			// if (fMembersToMove.length > 0)
			// TODO: Check project compliance.
			// status.merge(checkProjectCompliance(
			// getCompilationUnitRewrite(compilationUnitRewrites,
			// getDeclaringType().getCompilationUnit()),
			// getDestinationType(), fMembersToMove));

			// TODO: More checks, perhaps resembling those in
			// org.eclipse.jdt.internal.corext.refactoring.structure.PullUpRefactoringProcessor.checkFinalConditions(IProgressMonitor,
			// CheckConditionsContext).

			return status;
		} catch (Exception e) {
			JavaPlugin.log(e);
			throw e;
		} finally {
			monitor.done();
		}
	}

	protected static RefactoringStatus checkProjectCompliance(CompilationUnitRewrite sourceRewriter, IType destination,
			IMember[] members) {
		RefactoringStatus status = HierarchyProcessor.checkProjectCompliance(sourceRewriter, destination, members);

		if (!JavaModelUtil.is18OrHigher(destination.getJavaProject())) {
			Arrays.asList(members).stream().filter(e -> e instanceof IMethod).map(IMethod.class::cast)
					.filter(IMethod::isLambdaMethod)
					.forEach(m -> addError(status,
							Messages.MigrateSkeletalImplementationToInferfaceRefactoring_IncompatibleLanguageConstruct,
							m, destination));
		}

		return status;
	}

	@Override
	public Change createChange(IProgressMonitor pm) throws CoreException, OperationCanceledException {
		try {
			pm.beginTask(Messages.MigrateSkeletalImplementationToInferfaceRefactoring_CreatingChange, 1);

			CompilationUnit destinationCompilationUnit = this
					.getCompilationUnit(getDestinationInterface().getTypeRoot(), pm);
			ASTRewrite destinationRewrite = getASTRewrite(destinationCompilationUnit);
			final TextEditBasedChangeManager manager = new TextEditBasedChangeManager();

			Iterator<IMethod> methodsToMoveIterator = getMethodsToMoveIterator();
			while (methodsToMoveIterator.hasNext()) {
				IMethod sourceMethod = methodsToMoveIterator.next();
				logInfo("Migrating method: "
						+ JavaElementLabels.getElementLabel(sourceMethod, JavaElementLabels.ALL_FULLY_QUALIFIED)
						+ " to interface: " + destinationInterface.getFullyQualifiedName());

				CompilationUnit sourceCompilationUnit = getCompilationUnit(sourceMethod.getTypeRoot(), pm);

				MethodDeclaration sourceMethodDeclaration = ASTNodeSearchUtil.getMethodDeclarationNode(sourceMethod,
						sourceCompilationUnit);
				logInfo("Source method declaration: " + sourceMethodDeclaration);

				// Find the target method.
				IMethod targetMethod = getTargetMethod(sourceMethod);
				MethodDeclaration targetMethodDeclaration = ASTNodeSearchUtil.getMethodDeclarationNode(targetMethod,
						destinationCompilationUnit);

				// tack on the source method body to the target method.
				copyMethodBody(sourceMethodDeclaration, targetMethodDeclaration, destinationRewrite);

				// Change the target method to default.
				convertToDefault(targetMethodDeclaration, destinationRewrite);

				// Remove the source method.
				ASTRewrite sourceRewrite = getASTRewrite(sourceCompilationUnit);
				removeMethod(sourceMethodDeclaration, sourceRewrite);

				// save the source changes.
				// TODO: Need to deal with imports #22.
				if (!manager.containsChangesIn(sourceMethod.getCompilationUnit()))
					manageCompilationUnit(manager, sourceMethod.getCompilationUnit(), sourceRewrite);
			}

			if (!manager.containsChangesIn(getDestinationInterface().getCompilationUnit()))
				manageCompilationUnit(manager, getDestinationInterface().getCompilationUnit(), destinationRewrite);

			final Map<String, String> arguments = new HashMap<>();
			int flags = RefactoringDescriptor.STRUCTURAL_CHANGE | RefactoringDescriptor.MULTI_CHANGE;

			MigrateSkeletalImplementationToInterfaceRefactoringDescriptor descriptor = new MigrateSkeletalImplementationToInterfaceRefactoringDescriptor(
					null, "TODO", null, arguments, flags);

			return new DynamicValidationRefactoringChange(descriptor, getProcessorName(), manager.getAllChanges());
		} finally {
			pm.done();
		}
	}

	private CompilationUnit getCompilationUnit(ITypeRoot root, IProgressMonitor pm) {
		CompilationUnit compilationUnit = this.typeRootToCompilationUnitMap.get(root);
		if (compilationUnit == null) {
			compilationUnit = RefactoringASTParser.parseWithASTProvider(root, false, pm);
			this.typeRootToCompilationUnitMap.put(root, compilationUnit);
		}
		return compilationUnit;
	}

	private ASTRewrite getASTRewrite(CompilationUnit compilationUnit) {
		ASTRewrite rewrite = this.compilationUnitToASTRewriteMap.get(compilationUnit);
		if (rewrite == null) {
			rewrite = ASTRewrite.create(compilationUnit.getAST());
			this.compilationUnitToASTRewriteMap.put(compilationUnit, rewrite);
		}
		return rewrite;
	}

	private void manageCompilationUnit(final TextEditBasedChangeManager manager, ICompilationUnit compilationUnit,
			ASTRewrite rewrite) throws JavaModelException {
		TextEdit edit = rewrite.rewriteAST();

		TextChange change = (TextChange) manager.get(compilationUnit);
		change.setTextType("java");

		if (change.getEdit() == null)
			change.setEdit(edit);
		else
			change.addEdit(edit);

		manager.manage(compilationUnit, change);
	}

	private void copyMethodBody(MethodDeclaration sourceMethodDeclaration, MethodDeclaration targetMethodDeclaration,
			ASTRewrite destinationRewrite) {
		Block sourceMethodBody = sourceMethodDeclaration.getBody();
		Assert.isNotNull(sourceMethodBody, "Source method has a null body.");

		ASTNode sourceMethodBodyCopy = ASTNode.copySubtree(destinationRewrite.getAST(), sourceMethodBody);
		destinationRewrite.set(targetMethodDeclaration, MethodDeclaration.BODY_PROPERTY, sourceMethodBodyCopy, null);
	}

	private void removeMethod(MethodDeclaration methodDeclaration, ASTRewrite rewrite) {
		// TODO: Do I need an edit group??
		rewrite.remove(methodDeclaration, null);
	}

	private void convertToDefault(MethodDeclaration methodDeclaration, ASTRewrite rewrite) {
		Modifier modifier = rewrite.getAST().newModifier(ModifierKeyword.DEFAULT_KEYWORD);
		ListRewrite listRewrite = rewrite.getListRewrite(methodDeclaration, methodDeclaration.getModifiersProperty());
		listRewrite.insertLast(modifier, null);
	}

	/**
	 * Finds the target (interface) method declaration for the given source
	 * method.
	 * 
	 * @param sourceMethod
	 *            The method that will be migrated to the target interface.
	 * @return The target method that will be manipulated or null if not found.
	 */
	private IMethod getTargetMethod(IMethod sourceMethod) {
		IMethod[] methods = this.getDestinationInterface().findMethods(sourceMethod);
		Assert.isTrue(methods.length <= 1,
				"Found multiple target methods for method: " + sourceMethod.getElementName());
		if (methods.length == 1)
			return methods[0];
		else
			return null; // not found.
	}

	private void log(int severity, String message) {
		String name = FrameworkUtil.getBundle(this.getClass()).getSymbolicName();
		IStatus status = new Status(severity, name, message);
		JavaPlugin.log(status);
	}

	private void logInfo(String message) {
		log(IStatus.INFO, message);
	}

	private void logWarning(String message) {
		log(IStatus.WARNING, message);
	}

	@Override
	public String getIdentifier() {
		return MigrateSkeletalImplementationToInterfaceRefactoringDescriptor.REFACTORING_ID;
	}

	@Override
	public String getProcessorName() {
		return Messages.MigrateSkeletalImplementationToInferfaceRefactoring_Name;
	}

	@Override
	public boolean isApplicable() throws CoreException {
		return RefactoringAvailabilityTester.isInterfaceMigrationAvailable(getMethodsToMove());
	}

	public IMethod[] getMigratableMembersOfDeclaringType() {
		try {
			return RefactoringAvailabilityTester.getMigratableSkeletalImplementations(getDeclaringType());
		} catch (JavaModelException e) {
			return new IMethod[0];
		}
	}

	@Override
	protected RefactoringStatus checkConstructorCalls(IType type, IProgressMonitor monitor) throws JavaModelException {
		// TODO Auto-generated method stub
		return super.checkConstructorCalls(type, monitor);
	}

	/**
	 * @return the destinationType
	 */
	public IType getDestinationInterface() {
		return destinationInterface;
	}

	/**
	 * Sets the destination interface.
	 * 
	 * @param destinationInterface
	 *            The destination interface.
	 * @throws JavaModelException
	 */
	public void setDestinationType(IType destinationInterface) throws JavaModelException {
		Assert.isNotNull(destinationInterface);

		// TODO: Cache type hierarchy?
		this.destinationInterface = destinationInterface;
	}

	/**
	 * Returns true if the given type is a pure interface, i.e., it is an
	 * interface but not an annotation.
	 * 
	 * @param type
	 *            The type to check.
	 * @return True if the given type is a pure interface and false otherwise.
	 * @throws JavaModelException
	 */
	private static boolean isPureInterface(IType type) throws JavaModelException {
		return type != null && type.isInterface() && !type.isAnnotation();
	}

	@Override
	protected void rewriteTypeOccurrences(TextEditBasedChangeManager manager, ASTRequestor requestor,
			CompilationUnitRewrite rewrite, ICompilationUnit unit, CompilationUnit node, Set<String> replacements,
			IProgressMonitor monitor) throws CoreException {
		// TODO Auto-generated method stub
	}
}