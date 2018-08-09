package visitor.firstpass;

import antlr4.GolangBaseVisitor;
import antlr4.GolangParser;
import org.antlr.v4.runtime.tree.TerminalNode;
import util.ConstantString;
import visitor.SingleCollect;

import java.util.ArrayList;
import java.util.Stack;

/**
 * nodes visitor in the first visit
 */
public class EntityVisitor extends GolangBaseVisitor<String> {

    private ContextHelper helperVisitor = new ContextHelper();
    private ProcessTask processTask = new ProcessTask();
    SingleCollect singleCollect = SingleCollect.getSingleCollectInstance();

    private String fileFullPath;
    private int packageIndex;
    private int fileIndex;
    private int functionIndex = -1;

    //blockstack
    private Stack<Integer> blockStackForAFuncMeth = new Stack<Integer>();

    //// such as structFields, or interface Fields.
    private ArrayList<Integer> tmpEntitiesIds = new ArrayList<Integer>();

    public EntityVisitor(String fileFullPath) {
        this.fileFullPath = fileFullPath;
    }

    /*
    packageClause : 'package' IDENTIFIER;
     */
    @Override
    public String visitPackageClause(GolangParser.PackageClauseContext ctx) {
        if(ctx == null) {
            return null;
        }
        if(ctx.IDENTIFIER() == null) {
            return null;
        }
        // process packageEntity
        String packageName = ctx.IDENTIFIER().getText();
        String packagePath = processTask.getPackagePath(fileFullPath);

        int index = processTask.searchPackageIndex(packagePath);
        if (index != -1) {
            packageIndex = index;
        } else {
            packageIndex = processTask.processPackageDecl(packagePath, packageName);
        }

        fileIndex = processTask.processFile(packageIndex, fileFullPath);
        return null;
    }


    /**
     * importDecl: 'import' ( importSpec | '(' ( importSpec eos )* ')' );
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitImportDecl(GolangParser.ImportDeclContext ctx) {
        for (GolangParser.ImportSpecContext importSpecContext : ctx.importSpec()) {
            String importNameAndPath = visitImportSpec(importSpecContext);
            processTask.processImport(importNameAndPath, fileIndex);
        }
        return null;
    }


    /**
     * importSpec: ( '.' | IDENTIFIER )? importPath;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitImportSpec(GolangParser.ImportSpecContext ctx) {
        String importName = "";
        String importPath = "";

        if (ctx.getChild(0).equals(".")) {
            importName = ".";
        } else if (ctx.IDENTIFIER() != null) {
            importName = ctx.IDENTIFIER().getText();
        }

        importPath = visitImportPath(ctx.importPath());
        return importName + ";" + importPath;

    }

    /**
     * importPath: STRING_LIT;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitImportPath(GolangParser.ImportPathContext ctx) {
        return ctx.STRING_LIT().getText();
    }

    /**
     * grammar: constSpec: identifierList ( type? '=' expressionList )?;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitConstSpec(GolangParser.ConstSpecContext ctx) {
        String type = "";
        if (ctx.type() != null) {
            type = visitType(ctx.type());
        }
        //in file scope
        if (ctx.getParent() != null && helperVisitor.isTopLevelDecl(ctx.getParent())) {
            processTask.processConstInFile(ctx, type, fileIndex);
        }
        //in function scope
        else if(functionIndex != -1) {
            int localBlockId = functionIndex;
            if(!blockStackForAFuncMeth.isEmpty()) {
                localBlockId = blockStackForAFuncMeth.peek();
            }
            processTask.processConstInFunction(ctx, type, functionIndex, localBlockId);
        }
        return null;

    }



    /**
     * grammar
     * varDecl: 'var' ( varSpec | '(' ( varSpec eos )* ')' );
     * varSpec: identifierList ( type ( '=' expressionList )? | '=' expressionList );
     * identifierList: IDENTIFIER ( ',' IDENTIFIER )*;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitVarDecl(GolangParser.VarDeclContext ctx) {
        for (GolangParser.VarSpecContext varSpecContext : ctx.varSpec()) {
            String type;

            //get type of var
            if (varSpecContext.type() != null) {
                type = varSpecContext.type().getText();
            } else {
                type = "";
            }

            for (TerminalNode node : varSpecContext.identifierList().IDENTIFIER())
            {
                //value has a problem????????????
                String value = "";
                if (varSpecContext.expressionList() != null) {
                    value = visitExpressionList(varSpecContext.expressionList());
                }

                //the vars appears in file scope
                if (helperVisitor.isTopLevelDecl(ctx)) {
                    processTask.processVarDeclInFile(varSpecContext, type, fileIndex);
                }
                // the vars appear in function/method scope
                else if (functionIndex != -1) {
                    int localBlockId = functionIndex;
                    if (!blockStackForAFuncMeth.isEmpty()) {
                        localBlockId = blockStackForAFuncMeth.peek();
                    }
                    processTask.processVarInFunction(node, type, value,  functionIndex, localBlockId);
                } else {
                    //unknown
                }
            }
        }
        return null;
    }


    /**
     * typeSpec: IDENTIFIER type;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitTypeSpec(GolangParser.TypeSpecContext ctx) {

        //If it is StructType declaration.
        if (ctx.type().typeLit() != null && ctx.type().typeLit().structType() != null) {
            GolangParser.StructTypeContext structTypeContext = ctx.type().typeLit().structType();
            //find its struct fields, store into tmpEntitiesIds.
            visitStructType(structTypeContext);
            processTask.processTypeSpec(ctx, ConstantString.STRUCT_TYPE, tmpEntitiesIds, fileIndex);
            tmpEntitiesIds.clear();
        }

        //If it is InterfaceType declaration
        else if (ctx.type().typeLit() != null && ctx.type().typeLit().interfaceType() != null) {
            GolangParser.InterfaceTypeContext interfaceTypeContext = ctx.type().typeLit().interfaceType();
            //find its interface fields, store into tmpEntitiesIds.
            visitInterfaceType(interfaceTypeContext);
            processTask.processTypeSpec(ctx, ConstantString.INTERFACE_TYPE, tmpEntitiesIds, fileIndex);
            tmpEntitiesIds.clear();
        }


        //if it is AliasType declaration (typeName() - basicType)
        else if (ctx.type().typeName() != null && helperVisitor.isTopLevelDecl(ctx.getParent())) {
            String type = visitTypeName(ctx.type().typeName());
            String name = ctx.IDENTIFIER().getText();
            processTask.processAliasType(fileIndex, type, name);
        }

        //if it is AliasType declaration: (typeList() - slice/map/func type)
        else if (ctx.type().typeLit() != null
                && helperVisitor.isTopLevelDecl(ctx.getParent())) {
            String type = visitTypeLit(ctx.type().typeLit());
            String name = ctx.IDENTIFIER().getText();
            processTask.processAliasType(fileIndex, type, name);
        }

        return null;
    }



    /**
     * Get all fileds of interface, and store in tmpInterfacefields.
     * grammar:
     * structType: 'struct' '{' ( fieldDecl eos )* '}';
     * fieldDecl: (identifierList type | anonymousField) STRING_LIT?;
     * anonymousField: '*'? typeName;
     * identifierList: IDENTIFIER ( ',' IDENTIFIER )*;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitStructType(GolangParser.StructTypeContext ctx) {
        if (!helperVisitor.isStructTypeInTypeDecl(ctx)) {
            String str = "struct{";
            if (ctx.fieldDecl() != null && !ctx.fieldDecl().isEmpty()) {
                str += visitFieldDecl(ctx.fieldDecl(0));
                for (int i = 1; i < ctx.fieldDecl().size(); i++) {
                    str += (";" + visitFieldDecl(ctx.fieldDecl(i)));
                }
            }
            str += "}";
            return str;
        } else {
            tmpEntitiesIds.clear();
            if (ctx.fieldDecl() != null) {
                for (GolangParser.FieldDeclContext fieldDeclContext : ctx.fieldDecl()) {
                    String fieldType = null;
                    String fieldName = null;
                    if (fieldDeclContext.identifierList() != null) {
                        fieldType = fieldDeclContext.type().getText();
                        for (TerminalNode node : fieldDeclContext.identifierList().IDENTIFIER()) {
                            fieldName = node.getText();
                            int fieldIndex = processTask.processStructFieldAsNormal(fieldType, fieldName);
                            tmpEntitiesIds.add(fieldIndex);
                        } //end for
                    } //end if

                    else if (fieldDeclContext.anonymousField() != null) {
                        fieldName = ConstantString.STRUCT_FIELD_IS_ANONYMOUS; //default
                        fieldType = visitTypeName(fieldDeclContext.anonymousField().typeName());
                        int fieldIndex = processTask.processStructFieldAsAnonymous(fieldType, fieldName);
                        tmpEntitiesIds.add(fieldIndex);
                    } // end else
                } //end for
            }
            return null;
        }
    }


    /**
     * fieldDecl: (identifierList type | anonymousField) STRING_LIT?;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitFieldDecl(GolangParser.FieldDeclContext ctx) {
        String str = "";
        if (ctx.type() != null) {
            str += visitIdentifierList(ctx.identifierList());
            str += visitType(ctx.type());
        } else {
            str += visitAnonymousField(ctx.anonymousField());
        }
        if (ctx.STRING_LIT() != null) {
            str += ctx.STRING_LIT().getText();
        }
        return str;
    }


    /**
     * anonymousField: '*'? typeName;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitAnonymousField(GolangParser.AnonymousFieldContext ctx) {
        String str = visitTypeName(ctx.typeName());
        if (ctx.getChild(0).equals("*")) {
            return ("*" + str);
        } else {
            return str;
        }
    }

    /**
     * Get all fields of interface, and store in tmpInterfacefields.
     * grammar:
     * interfaceType: 'interface' '{' ( methodSpec eos )* '}';
     * methodSpec: IDENTIFIER signature  |  typeName;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitInterfaceType(GolangParser.InterfaceTypeContext ctx) {
        //it is not for interface type declaration
        if (!helperVisitor.isInterfaceypeInTypeDecl(ctx)) {
            String str = "interface{";
            if (ctx.methodSpec().size() != 0) {
                str += visitMethodSpec(ctx.methodSpec(0));
                for (int i = 1; i < ctx.methodSpec().size(); i++) {
                    str += (";" + visitMethodSpec(ctx.methodSpec(i)));
                }
            }
            str += "}";
            return str;
        }
        //it is for interface type declaration
        else {
            //tmpEntities.clear();
            tmpEntitiesIds.clear();
            if (ctx.methodSpec() != null) {
                for (GolangParser.MethodSpecContext methodSpecContext : ctx.methodSpec()) {
                    String type = null; // "TYPE" or "METHOD"
                    String name = null;
                    //TypeDecl as a field
                    if (methodSpecContext.typeName() != null) {
                        type = ConstantString.INTERFACE_FIELD_IS_TYPE; //"TYPE"
                        name = visitTypeName(methodSpecContext.typeName());
                        int fieldIndex = processTask.processInterfaceFieldAsType(type, name);
                        tmpEntitiesIds.add(fieldIndex);
                    }
                    //methodDecl as a field
                    else {
                        type = ConstantString.INTERFACE_FIELD_IS_METHOD; //"METHOD";
                        name = methodSpecContext.IDENTIFIER().getText();
                        //parse methodSignature, grammar: signature: parameters result?;
                        String methodSignatureParas = visitParameters(methodSpecContext.signature().parameters());
                        String methodSignatureReturns = "";
                        if (methodSpecContext.signature().result() != null) {
                            methodSignatureReturns = visitResult(methodSpecContext.signature().result());
                        }
                        int fieldIndex = processTask.processInterfaceFieldAsMethod(type, name, methodSignatureParas, methodSignatureReturns);
                        tmpEntitiesIds.add(fieldIndex);
                    }
                }
            }
        } //end else
        return null;
    }




    /**
     * methodSpec: IDENTIFIER signature  |  typeName;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitMethodSpec(GolangParser.MethodSpecContext ctx) {
        if (ctx == null) {
            System.out.println("ctx is null");
        }
        if (ctx.typeName() != null) {
            return visitTypeName(ctx.typeName());
        } else {
            return ctx.IDENTIFIER().getText() + visitSignature(ctx.signature());
        }
    }

    /**
     * typeLit: arrayType | structType | pointerType | functionType | interfaceType | sliceType | mapType | channelType ;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitTypeLit(GolangParser.TypeLitContext ctx) {
        if (ctx.arrayType() != null) {
            return visitArrayType(ctx.arrayType());
        } else if (ctx.structType() != null) {
            return visitStructType(ctx.structType());
        } else if (ctx.interfaceType() != null) {
            return visitInterfaceType(ctx.interfaceType());
        } else if (ctx.pointerType() != null) {
            return visitPointerType(ctx.pointerType());
        } else if (ctx.functionType() != null) {
            return visitFunctionType(ctx.functionType());
        } else if (ctx.sliceType() != null) {
            return visitSliceType(ctx.sliceType());
        } else if (ctx.mapType() != null) {
            return visitMapType(ctx.mapType());
        } else if (ctx.channelType() != null) {
            return visitChannelType(ctx.channelType());
        }
        return null;
    }

    /**
     * arrayType: '[' arrayLength ']' elementType;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitArrayType(GolangParser.ArrayTypeContext ctx) {
        return "[" + visitArrayLength(ctx.arrayLength()) + "]" + visitElementType(ctx.elementType());
    }

    /**
     * pointerType: '*' type;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitPointerType(GolangParser.PointerTypeContext ctx) {
        return "*" + visitType(ctx.type());
    }

    /**
     * functionType: 'func' signature;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitFunctionType(GolangParser.FunctionTypeContext ctx) {
        return "func " + visitSignature(ctx.signature());
    }

    /**
     * sliceType: '[' ']' elementType;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitSliceType(GolangParser.SliceTypeContext ctx) {
        return "[]" + visitElementType(ctx.elementType());
    }

    /**
     * mapType: 'map' '[' type ']' elementType;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitMapType(GolangParser.MapTypeContext ctx) {
        return "map" + "[" + visitType(ctx.type()) + "]" + visitElementType(ctx.elementType());
    }

    /**
     * channelType: ( 'chan' | 'chan' '<-' | '<-' 'chan' ) elementType;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitChannelType(GolangParser.ChannelTypeContext ctx) {
        return ctx.getChild(0).getText() + visitElementType(ctx.elementType());
    }

    /**
     * elementType: type;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitElementType(GolangParser.ElementTypeContext ctx) {
        return visitType(ctx.type());
    }

    /**
     * type: typeName | typeLit | '(' type ')';
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitType(GolangParser.TypeContext ctx) {
        if (ctx.typeName() != null) {
            return visitTypeName(ctx.typeName());
        } else if (ctx.typeLit() != null) {
            return visitTypeLit(ctx.typeLit());
        } else if (ctx.type() != null) {
            return ("(" + visitType(ctx.type()) + ")");
        }
        return null;
    }

    /**
     * typeName: IDENTIFIER | qualifiedIdent;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitTypeName(GolangParser.TypeNameContext ctx) {
        if (ctx.IDENTIFIER() != null) {
            return ctx.IDENTIFIER().getText();
        } else if (ctx.qualifiedIdent() != null) {
            return visitQualifiedIdent(ctx.qualifiedIdent());
        }
        return null;
    }

    /**
     * qualifiedIdent: IDENTIFIER '.' IDENTIFIER;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitQualifiedIdent(GolangParser.QualifiedIdentContext ctx) {
        return ctx.IDENTIFIER(0).getText() + "." + ctx.IDENTIFIER(1).getText();
    }

    /**
     * signature: parameters result?;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitSignature(GolangParser.SignatureContext ctx) {
        if (ctx.result() != null) {
            return visitParameters(ctx.parameters()) + " " + visitResult(ctx.result());
        } else {
            return visitParameters(ctx.parameters());
        }
    }

    /**
     * parameters: '(' ( parameterList ','? )? ')';
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitParameters(GolangParser.ParametersContext ctx) {
        if (ctx.parameterList() != null) {
            String str = "(";
            str += visitParameterList(ctx.parameterList());
            if (ctx.getChildCount() == 4) {
                str += ",";
            }
            str += ")";
            return str;
        } else {
            return "()";
        }
    }

    /**
     * parameterList: parameterDecl ( ',' parameterDecl )*
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitParameterList(GolangParser.ParameterListContext ctx) {
        String str = "";
        str += visitParameterDecl(ctx.parameterDecl(0));
        if (ctx.parameterDecl().size() > 1) {
            for (int i = 1; i < ctx.parameterDecl().size(); i++) {
                str += ("," + visitParameterDecl(ctx.parameterDecl(i)));
            }
        }
        return str;
    }

    /**
     * parameterDecl: identifierList? '...'? type;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitParameterDecl(GolangParser.ParameterDeclContext ctx) {
        String str = "";
        //parameters (having identifierList)
        if (ctx.identifierList() != null) {
            str += visitIdentifierList(ctx.identifierList());

            if (ctx.getChildCount() >= 2 &&
                    (ctx.getChild(0).getText().equals("...") || ctx.getChild(1).getText().equals("..."))) {
                str += " ...";
                str += visitType(ctx.type());
            } else {
                str += (" " + visitType(ctx.type()));
            }
        }
        //returns (having no identifierList, just having type)
        else {
            str += visitType(ctx.type());
        }

        return str;
    }





    /**
     * identifierList: IDENTIFIER ( ',' IDENTIFIER )*;
     * @param ctx
     * @return
     */
    @Override
    public String visitIdentifierList(GolangParser.IdentifierListContext ctx) {
        //processTask.processIdentifierList(ctx, functionIndex);

        String str = "";
        str += ctx.IDENTIFIER(0).getText();
        if (ctx.IDENTIFIER().size() > 1) {
            for (int i = 1; i < ctx.IDENTIFIER().size(); i++) {
                str += ("," + ctx.IDENTIFIER(i).getText());
            }
        }
        return str;
    }

    /**
     * result : parameters | type;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitResult(GolangParser.ResultContext ctx) {
        if (ctx.parameters() != null) {
            return visitParameters(ctx.parameters());
        } else if (ctx.type() != null) {
            return visitType(ctx.type());
        }
        return null;
    }

    /**
     * arrayLength: expression;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitArrayLength(GolangParser.ArrayLengthContext ctx) {
        return visitExpression(ctx.expression());
    }

    /**
     * expression: unaryExpr
     * | expression ('||' | '&&' | '==' | '!=' | '<' | '<=' | '>' | '>=' | '+' | '-' | '|' | '^' | '*' | '/' | '%' | '<<' | '>>' | '&' | '&^') expression
     * ;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitExpression(GolangParser.ExpressionContext ctx) {
        if (ctx == null) {
            System.out.println("visitExpression null");
        }
        String str;
        if (ctx.unaryExpr() != null) {
            str = visitUnaryExpr(ctx.unaryExpr());
        } else {
            str = visitExpression(ctx.expression(0)) + ctx.getChild(1).getText() + visitExpression(ctx.expression(1));
        }
        //visitChildren(ctx);
        return str;
    }

    /**
     * unaryExpr: primaryExpr   | ('+'|'-'|'!'|'^'|'*'|'&'|'<-') unaryExpr;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitUnaryExpr(GolangParser.UnaryExprContext ctx) {
        if (ctx == null) {
            System.out.println("visitUnaryExpr null");
        }
        if (ctx.primaryExpr() != null) {
            return visitPrimaryExpr(ctx.primaryExpr());
        } else if (ctx.unaryExpr() != null) {
            return ctx.getChild(0).getText() + visitUnaryExpr(ctx.unaryExpr());
        }
        return null;
    }


    /**
     * primaryExpr: operand                     #operandPrimaryExpr;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitOperandPrimaryExpr(GolangParser.OperandPrimaryExprContext ctx) {
        String str = "";
        if (ctx != null) {
            str = visitOperand(ctx.operand());
            //processTask.processOperandInFunctionEntity(ctx, str, functionIndex);
        }
        return str;
    }

    /**
     * primaryExpr: conversion                  #conversionPrimaryExpr;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitConversionPrimaryExpr(GolangParser.ConversionPrimaryExprContext ctx) {
        String str = "";
        if (ctx != null) {
            str = visitConversion(ctx.conversion());
            //processTask.processOperandInFunctionEntity(ctx, str, functionIndex);
        }
        return str;
    }

    /**
     * primaryExpr: primaryExpr selector        #selectorPrimaryExpr;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitSelectorPrimaryExpr(GolangParser.SelectorPrimaryExprContext ctx) {
        String str = "";
        if (ctx != null) {
            str = visitPrimaryExpr(ctx.primaryExpr()) + visitSelector(ctx.selector());
            //processTask.processOperandInFunctionEntity(ctx, str, functionIndex);
        }
        return str;
    }

    /**
     * primaryExpr: primaryExpr index           #indexPrimaryExpr;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitIndexPrimaryExpr(GolangParser.IndexPrimaryExprContext ctx) {
        String str = "";
        if (ctx != null) {
            str = visitPrimaryExpr(ctx.primaryExpr()) + visitIndex(ctx.index());
            //processTask.processOperandInFunctionEntity(ctx, str, functionIndex);
        }
        return str;
    }

    /**
     * primaryExpr: primaryExpr slice           #slicePrimaryExpr;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitSlicePrimaryExpr(GolangParser.SlicePrimaryExprContext ctx) {
        String str = "";
        if (ctx != null) {
            str = visitPrimaryExpr(ctx.primaryExpr()) + visitSlice(ctx.slice());
        }
        return str;
    }

    /**
     * primaryExpr: primaryExpr typeAssertion   #typeAssertionPrimaryExpr;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitTypeAssertionPrimaryExpr(GolangParser.TypeAssertionPrimaryExprContext ctx) {
        String str = "";
        if (ctx != null) {
            str = visitPrimaryExpr(ctx.primaryExpr()) + visitTypeAssertion(ctx.typeAssertion());
        }
        return str;
    }

    /**
     * primaryExpr:| primaryExpr arguments       #methodCallPrimaryExpr;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitMethodCallPrimaryExpr(GolangParser.MethodCallPrimaryExprContext ctx) {
        String str = "";
        if (ctx != null) {
            str = visitPrimaryExpr(ctx.primaryExpr()) + visitArguments(ctx.arguments());
        }

        processTask.processMethodCallPrimaryExpr(functionIndex, str);
        return str;
    }


    /**
     * primaryExpr
     * : operand                     #operandPrimaryExpr
     * | conversion                  #conversionPrimaryExpr
     * | primaryExpr selector        #selectorPrimaryExpr
     * | primaryExpr index           #indexPrimaryExpr
     * | primaryExpr slice           #slicePrimaryExpr
     * | primaryExpr typeAssertion   #typeAssertionPrimaryExpr
     * | primaryExpr arguments       #methodCallPrimaryExpr
     * ;
     **/
    private String visitPrimaryExpr(GolangParser.PrimaryExprContext ctx) {
        if (ctx instanceof GolangParser.OperandPrimaryExprContext) {
            return visitOperandPrimaryExpr((GolangParser.OperandPrimaryExprContext) ctx);
        } else if (ctx instanceof GolangParser.ConversionPrimaryExprContext) {
            return visitConversionPrimaryExpr((GolangParser.ConversionPrimaryExprContext) ctx);
        } else if (ctx instanceof GolangParser.SelectorPrimaryExprContext) {
            return visitSelectorPrimaryExpr((GolangParser.SelectorPrimaryExprContext) ctx);
        } else if (ctx instanceof GolangParser.IndexPrimaryExprContext) {
            return visitIndexPrimaryExpr((GolangParser.IndexPrimaryExprContext) ctx);
        } else if (ctx instanceof GolangParser.SlicePrimaryExprContext) {
            return visitSlicePrimaryExpr((GolangParser.SlicePrimaryExprContext) ctx);
        } else if (ctx instanceof GolangParser.TypeAssertionPrimaryExprContext) {
            return visitTypeAssertionPrimaryExpr((GolangParser.TypeAssertionPrimaryExprContext) ctx);
        } else if (ctx instanceof GolangParser.MethodCallPrimaryExprContext) {
            return visitMethodCallPrimaryExpr((GolangParser.MethodCallPrimaryExprContext) ctx);
        }
        return null;
    }


    /**
     * operand : literal | operandName | methodExpr | '(' expression ')';
     * @param ctx
     * @return
     */
    @Override
    public String visitOperand(GolangParser.OperandContext ctx) {
        if (ctx.literal() != null) {
            return visitLiteral(ctx.literal());
        } else if (ctx.operandName() != null) {
            String operandName = visitOperandName(ctx.operandName());
            return operandName;
        } else if (ctx.methodExpr() != null) {
            return visitMethodExpr(ctx.methodExpr());
        } else if (ctx.expression() != null) {
            return "(" + visitExpression(ctx.expression()) + ")";
        } else {
            return null;
        }
    }

    /**
     * operandName: IDENTIFIER | qualifiedIdent;
     * @param ctx
     * @return
     */
    @Override
    public String visitOperandName(GolangParser.OperandNameContext ctx) {
        String str = "";
        if (ctx.IDENTIFIER() != null) {
            str = ctx.IDENTIFIER().getText();
        } else {
            str =  visitQualifiedIdent(ctx.qualifiedIdent());
        }
        if(functionIndex != -1) {
            processTask.processOperandNameInFunction(str, ctx, functionIndex);
        }

        return str;
    }



    /**
     * methodExpr: receiverType '.' IDENTIFIER;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitMethodExpr(GolangParser.MethodExprContext ctx) {
        return visitReceiverType(ctx.receiverType()) + "." + ctx.IDENTIFIER().getText();
    }

    /**
     * receiverType: typeName   | '(' '*' typeName ')'   |    '(' receiverType ')';
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitReceiverType(GolangParser.ReceiverTypeContext ctx) {
        if (ctx.getChildCount() == 1 && ctx.typeName() != null) {
            return visitTypeName(ctx.typeName());
        } else if (ctx.getChildCount() > 1 && ctx.typeName() != null) {
            return "(*" + visitTypeName(ctx.typeName()) + ")";
        } else if (ctx.getChildCount() > 1 && ctx.receiverType() != null) {
            return "(" + visitReceiverType(ctx.receiverType()) + ")";
        }
        return null;
    }

    /**
     * literal : basicLit | compositeLit  | functionLit;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitLiteral(GolangParser.LiteralContext ctx) {
        if (ctx.basicLit() != null) {
            return visitBasicLit(ctx.basicLit());
        } else if (ctx.compositeLit() != null) {
            return visitCompositeLit(ctx.compositeLit());
        } else if (ctx.functionLit() != null) {
            return visitFunctionLit(ctx.functionLit());
        }
        return null;
    }

    /**
     * basicLit : INT_LIT  | FLOAT_LIT  | IMAGINARY_LIT  | RUNE_LIT   | STRING_LIT;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitBasicLit(GolangParser.BasicLitContext ctx) {
        if (ctx.INT_LIT() != null) {
            return ctx.INT_LIT().getText();
        } else if (ctx.FLOAT_LIT() != null) {
            return ctx.FLOAT_LIT().getText();
        } else if (ctx.IMAGINARY_LIT() != null) {
            return ctx.IMAGINARY_LIT().getText();
        } else if (ctx.RUNE_LIT() != null) {
            return ctx.RUNE_LIT().getText();
        } else if (ctx.STRING_LIT() != null) {
            return ctx.STRING_LIT().getText();
        }
        return null;
    }

    /**
     * compositeLit: literalType literalValue;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitCompositeLit(GolangParser.CompositeLitContext ctx) {
        return visitLiteralType(ctx.literalType()) + visitLiteralValue(ctx.literalValue());
    }

    /**
     * literalType : structType | arrayType | '[' '...' ']' elementType | sliceType | mapType | typeName;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitLiteralType(GolangParser.LiteralTypeContext ctx) {
        if (ctx.structType() != null) {
            return visitStructType(ctx.structType());
        } else if (ctx.arrayType() != null) {
            return visitArrayType(ctx.arrayType());
        } else if (ctx.elementType() != null) {
            return "[...]" + visitElementType(ctx.elementType());
        } else if (ctx.sliceType() != null) {
            return visitSliceType(ctx.sliceType());
        } else if (ctx.mapType() != null) {
            return visitMapType(ctx.mapType());
        } else if (ctx.typeName() != null) {
            return visitTypeName(ctx.typeName());
        }
        return null;
    }

    /**
     * literalValue : '{' ( elementList ','? )? '}'  ;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitLiteralValue(GolangParser.LiteralValueContext ctx) {
        if (ctx.elementList() != null) {
            return ("{" + visitElementList(ctx.elementList()) + "}");
        } else {
            return "{}";
        }
    }

    /**
     * elementList: keyedElement (',' keyedElement)*;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitElementList(GolangParser.ElementListContext ctx) {
        String str = visitKeyedElement(ctx.keyedElement(0));
        if (ctx.keyedElement().size() > 1) {
            for (int i = 1; i < ctx.keyedElement().size(); i++) {
                str += ("," + visitKeyedElement(ctx.keyedElement(i)));
            }
        }
        return str;
    }

    /**
     * keyedElement: (key ':')? element;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitKeyedElement(GolangParser.KeyedElementContext ctx) {
        String str = "";
        if (ctx.key() != null) {
            str += ((visitKey(ctx.key())) + ":");
        }
        if (ctx.element() != null) {
            str += visitElement(ctx.element());
        }
        return str;
    }

    /**
     * key: IDENTIFIER  | expression  | literalValue;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitKey(GolangParser.KeyContext ctx) {
        if (ctx.IDENTIFIER() != null) {
            return ctx.IDENTIFIER().getText();
        } else if (ctx.expression() != null) {
            return visitExpression(ctx.expression());
        } else if (ctx.literalValue() != null) {
            return visitLiteralValue(ctx.literalValue());
        }
        return null;
    }

    /**
     * element : expression  | literalValue;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitElement(GolangParser.ElementContext ctx) {
        if (ctx == null) {
            System.out.println("visitElement null");
        }
        if (ctx.expression() != null) {
            return visitExpression(ctx.expression());
        } else if (ctx.literalValue() != null) {
            return visitLiteralValue(ctx.literalValue());
        }
        return null;
    }

    /**
     * functionLit: 'func' function;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitFunctionLit(GolangParser.FunctionLitContext ctx) {
        return "func" + visitFunction(ctx.function());
    }

    /**
     * function: signature block;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitFunction(GolangParser.FunctionContext ctx) {
        return visitSignature(ctx.signature()) + visitBlock(ctx.block());
    }

    /**
     * block: '{' statementList '}';
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitBlock(GolangParser.BlockContext ctx) {
        visitChildren(ctx);
        return "{}";
    }

    /**
     * conversion: type '(' expression ','? ')';
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitConversion(GolangParser.ConversionContext ctx) {
        String str = "";
        str += visitType(ctx.type());
        str += "(";
        str += visitExpression(ctx.expression());
        if (ctx.getChild(3) != null && ctx.getChild(3).getText().equals(",")) {
            str += ",";
        }
        str += ")";
        return str;
    }

    /**
     * selector: '.' IDENTIFIER;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitSelector(GolangParser.SelectorContext ctx) {
        return ("." + ctx.IDENTIFIER().getText());
    }

    /**
     * index: '[' expression ']';
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitIndex(GolangParser.IndexContext ctx) {
        return ("[" + visitExpression(ctx.expression()) + "]");
    }

    /**
     * slice: '[' (( expression? ':' expression? ) | ( expression? ':' expression ':' expression )) ']';
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitSlice(GolangParser.SliceContext ctx) {
        if (ctx.expression().size() == 0) {
            return "[:]";
        } else if (ctx.expression().size() == 1) {
            if (ctx.getChild(0).getText().equals(":")) {
                return "[:" + visitExpression(ctx.expression(0)) + "]";
            } else {
                return "[" + visitExpression(ctx.expression(0)) + ":]";
            }
        } else if (ctx.expression().size() == 2) {
            if (ctx.getChildCount() == 3) {
                return "[" + visitExpression(ctx.expression(0)) + ":" + visitExpression(ctx.expression(1)) + "]";
            } else {
                return "[:" + visitExpression(ctx.expression(0)) + ":" + visitExpression(ctx.expression(1)) + "]";
            }
        } else if (ctx.expression().size() == 3) {
            return "["
                    + visitExpression(ctx.expression(0)) + ":"
                    + visitExpression(ctx.expression(1)) + ":"
                    + visitExpression(ctx.expression(2)) + "]";
        } else {
            return null;
        }
    }

    /**
     * typeAssertion: '.' '(' type ')';
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitTypeAssertion(GolangParser.TypeAssertionContext ctx) {
        return (".(" + visitType(ctx.type()) + ")");
    }

    /**
     * arguments: '(' ( ( expressionList | type ( ',' expressionList )? ) '...'? ','? )? ')';
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitArguments(GolangParser.ArgumentsContext ctx) {
        if (ctx == null) {
            System.out.println("visitArguments null");
        }
        String str = "(";

        if (ctx.type() != null) {
            str += visitType(ctx.type());
            if (ctx.expressionList() != null) {
                str += ",";
                str += visitExpressionList(ctx.expressionList());
            }
        } else if (ctx.expressionList() != null) {
            str += visitExpressionList(ctx.expressionList());
        }
        str += ")";
        return str;
    }

    /**
     * expressionList   : expression ( ',' expression )*;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitExpressionList(GolangParser.ExpressionListContext ctx) {
        if (ctx == null) {
            System.out.println("visitExpressionList  null");
        }
        String str = visitExpression(ctx.expression(0));
        if (ctx.expression().size() > 1) {
            for (int i = 1; i < ctx.expression().size(); i++) {
                str += ("," + visitExpression(ctx.expression(i)));
            }
        }
        return str;
    }


    /**
     * grammar: functionDecl: 'func' IDENTIFIER ( function | signature );
     * function: signature block;
     * signature: parameters result?;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitFunctionDecl(GolangParser.FunctionDeclContext ctx) {
        String functionName = ctx.IDENTIFIER().getText();
        String parameters = "";
        String returns = "";
        if (ctx.function() != null) {
            parameters = visitParameters(ctx.function().signature().parameters());
            if (ctx.function().signature().result() != null) {
                returns = visitResult(ctx.function().signature().result());
            }
        } else if (ctx.signature() != null) {
            parameters = visitParameters(ctx.signature().parameters());
            if (ctx.signature().result() != null) {
                returns = visitResult(ctx.signature().result());
            }
        }
        functionIndex = processTask.processFunction(functionName, parameters, returns, fileIndex);
        blockStackForAFuncMeth.clear();

        if (ctx.function() != null) {
            visitBlock(ctx.function().block()); //add operandVar into function
        }
        singleCollect.getEntities().get(fileIndex).addChildId(functionIndex);
        functionIndex = -1;
        return null;
    }




    /**
     * Grammar:   methodDecl: 'func' receiver IDENTIFIER ( function | signature );
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitMethodDecl(GolangParser.MethodDeclContext ctx) {
        if(ctx == null) {
            return null;
        }
        String functionName = "";
        String receiverStr = "";
        String parameters = "";
        String returns = "";
        if(ctx.IDENTIFIER() != null) {
            functionName = ctx.IDENTIFIER().getText();
        }
        if(ctx.receiver() != null) {
            receiverStr = visitReceiver(ctx.receiver());
        }

        if (ctx.function() != null) {
            parameters = visitParameters(ctx.function().signature().parameters());
            if (ctx.function().signature().result() != null) {
                returns = visitResult(ctx.function().signature().result());
            }
        } else if (ctx.signature() != null) {
            parameters = visitParameters(ctx.signature().parameters());
            if (ctx.signature().result() != null) {
                returns = visitResult(ctx.signature().result());
            }
        }
        functionIndex =  processTask.processMethod(functionName, receiverStr, parameters, returns, fileIndex);
        blockStackForAFuncMeth.clear();

        if (ctx.function() != null) {
            visitBlock(ctx.function().block()); //add operandVar into function
        }
        singleCollect.getEntities().get(fileIndex).addChildId(functionIndex);
        functionIndex = -1;
        return null;
    }


    /**
     * receiver: parameters;
     * @param ctx
     * @return
     */
    @Override
    public String visitReceiver(GolangParser.ReceiverContext ctx) {
        String str = "";
        if (ctx.parameters() != null) {
            str = visitParameters(ctx.parameters());
        }
        return str;
    }


    /**
     * shortVarDecl: leftShortVarDecl ':=' rightShortVarDecl;
     * leftShortVarDecl: identifierList;
     * rightShortVarDecl: expressionList;
     *
     * @param ctx
     * @return
     */
    @Override
    public String visitShortVarDecl(GolangParser.ShortVarDeclContext ctx) {
        String leftOperands = visitLeftShortVarDecl(ctx.leftShortVarDecl());
        String rightExps = visitRightShortVarDecl(ctx.rightShortVarDecl());
        if (functionIndex != -1 && leftOperands != null && rightExps != null) {
            int localBlockId = functionIndex;
            if (!blockStackForAFuncMeth.isEmpty()) {
                localBlockId = blockStackForAFuncMeth.peek();
            }
            processTask.processShortDeclVarInFunction(leftOperands, rightExps, functionIndex, localBlockId);
        }
        return (leftOperands + ":=" + rightExps);
    }


    /**
     * leftShortVarDecl: identifierList;
     * @param ctx
     * @return
     */
    @Override
    public String visitLeftShortVarDecl(GolangParser.LeftShortVarDeclContext ctx) {
        String str = "";
        if(ctx != null) {
            str = visitIdentifierList(ctx.identifierList());
        }
        return str;
    }

    /**
     * rightShortVarDecl: expressionList;
     * @param ctx
     * @return
     */
    @Override
    public String visitRightShortVarDecl(GolangParser.RightShortVarDeclContext ctx) {
        String str = "";
        if(ctx != null) {
            str = visitExpressionList(ctx.expressionList());
        }
        return str;
    }

    /**
     * assignment: leftAssignment assign_op rightAssignment;
     * leftAssignment: expressionList;
     * rightAssignment: expressionList;
     * @param ctx
     * @return
     */
    @Override
    public String visitAssignment(GolangParser.AssignmentContext ctx) {
        String op = visitAssign_op(ctx.assign_op());
        String left = visitLeftAssignment(ctx.leftAssignment());
        String right = visitRightAssignment(ctx.rightAssignment());
        return (left + op + right);
    }


    /**
     * forStmt: 'for' ( expression | forClause | rangeClause )? block;
     * when entering, create a new for block and push blockStack, store this block.
     * when existing, pop blockStack.
     * @param ctx
     * @return
     */
    @Override
    public String visitForStmt(GolangParser.ForStmtContext ctx) {
        //new block
        int parentBlockId = -1;
        if (!blockStackForAFuncMeth.isEmpty()) {
            parentBlockId = blockStackForAFuncMeth.peek();
        }
        int depth = blockStackForAFuncMeth.size();
        int blockId = processTask.processForBlock(functionIndex, parentBlockId, depth);
        //push block stack
        blockStackForAFuncMeth.push(blockId);

        //visit children
        String str = "for";
        if (ctx == null) {
            return str;
        }
        str += "(";
        if(ctx.expression() != null) {
            str += visitExpression(ctx.expression());
        }
        if(ctx.forClause() != null) {
            str += visitForClause(ctx.forClause());
        }
        if(ctx.rangeClause() != null) {
            str += visitRangeClause(ctx.rangeClause());
        }
        str += ")";
        if(ctx.block() != null) {
            str += visitBlock(ctx.block());
        }

        //pop block stack
        blockStackForAFuncMeth.pop();
        return str;
    }

    /**
     * ifStmtIf: 'if' (simpleStmt ';')? expression block;
     * when entering, create a new for if and push blockStack, store this block.
     * when existing, pop blockStack.
     * @param ctx
     * @return
     */
    @Override
    public String visitIfStmtIf(GolangParser.IfStmtIfContext ctx) {
        //new block
        int parentBlockId = -1;
        if (!blockStackForAFuncMeth.isEmpty()) {
            parentBlockId = blockStackForAFuncMeth.peek();
        }
        int depth = blockStackForAFuncMeth.size();
        int blockId = processTask.processIfBlock(functionIndex, parentBlockId, depth);
        //push block stack
        blockStackForAFuncMeth.push(blockId);

        //visit children
        String str = "if";
        if (ctx == null) {
            return str;
        }
        str += "if";
        if (ctx.simpleStmt() != null) {
            str += visitSimpleStmt(ctx.simpleStmt());
        }
        if(ctx.expression() != null) {
            str += visitExpression(ctx.expression());
        }
        if(ctx.block() != null) {
            str += visitBlock(ctx.block());
        }

        //pop block stack
        blockStackForAFuncMeth.pop();

        return str;
    }

    /**
     * fStmtElse: 'else' ( ifStmt | block );
     * when entering, create a new for else and push blockStack, store this block.
     * when existing, pop blockStack.
     * @param ctx
     * @return
     */
    @Override
    public String visitIfStmtElse(GolangParser.IfStmtElseContext ctx) {
        //new block
        int parentBlockId = -1;
        if (!blockStackForAFuncMeth.isEmpty()) {
            parentBlockId = blockStackForAFuncMeth.peek();
        }
        int depth = blockStackForAFuncMeth.size();
        int blockId = processTask.processElseBlock(functionIndex, parentBlockId, depth);
        //push block stack
        blockStackForAFuncMeth.push(blockId);

        //visit children
        String str = "else";
        if (ctx == null) {
            return str;
        }
        if(ctx.ifStmt() != null) {
            str += visitIfStmt(ctx.ifStmt());
        }
        if(ctx.block() != null) {
            str += visitBlock(ctx.block());
        }

        //pop block stack
        blockStackForAFuncMeth.pop();
        return str;
    }

    /**
     * switchStmt: exprSwitchStmt | typeSwitchStmt;
     * when entering, create a new for switch and push blockStack, store this block.
     * when existing, pop blockStack.
     * @param ctx
     * @return
     */
    @Override
    public String visitSwitchStmt(GolangParser.SwitchStmtContext ctx) {
        //new block
        int parentBlockId = -1;
        if (!blockStackForAFuncMeth.isEmpty()) {
            parentBlockId = blockStackForAFuncMeth.peek();
        }
        int depth = blockStackForAFuncMeth.size();
        int blockId = processTask.processSwitchBlock(functionIndex, parentBlockId, depth);
        //push block stack
        blockStackForAFuncMeth.push(blockId);

        //visit children
        String str = "switch";
        if (ctx == null) {
            return str;
        }
        if(ctx.exprSwitchStmt() != null) {
            str += visitExprSwitchStmt(ctx.exprSwitchStmt());
        }
        if(ctx.typeSwitchStmt() != null) {
            str += visitTypeSwitchStmt(ctx.typeSwitchStmt());
        }

        //pop block stack
        blockStackForAFuncMeth.pop();
        return str;
    }

    /**
     * exprCaseClause: exprSwitchCase ':' statementList;
     * when entering, create a new swithc-case-clause block and push blockStack, store this block.
     * when existing, pop blockStack.
     * @param ctx
     * @return
     */
    @Override
    public String visitExprCaseClause(GolangParser.ExprCaseClauseContext ctx) {
        //new block
        int parentBlockId = -1;
        if (!blockStackForAFuncMeth.isEmpty()) {
            parentBlockId = blockStackForAFuncMeth.peek();
        }
        int depth = blockStackForAFuncMeth.size();
        int blockId = processTask.processSwitchCaseBlock(functionIndex, parentBlockId, depth);
        //push block stack
        blockStackForAFuncMeth.push(blockId);

        //visit children
        String str = "";
        if (ctx == null) {
            return str;
        }
        if(ctx.exprSwitchCase() != null) {
            str += visitExprSwitchCase(ctx.exprSwitchCase());
        }
        str+= ":";
        if(ctx.statementList() != null) {
            str += visitStatementList(ctx.statementList());
        }

        //pop block stack
        blockStackForAFuncMeth.pop();
        return str;
    }

    /**
     * typeCaseClause: typeSwitchCase ':' statementList;
     * when entering, create a new swithc-case-clause block and push blockStack, store this block.
     * when existing, pop blockStack.
     * @param ctx
     * @return
     */
    @Override
    public String visitTypeCaseClause(GolangParser.TypeCaseClauseContext ctx) {
        //new block
        int parentBlockId = -1;
        if (!blockStackForAFuncMeth.isEmpty()) {
            parentBlockId = blockStackForAFuncMeth.peek();
        }
        int depth = blockStackForAFuncMeth.size();
        int blockId = processTask.processSwitchCaseBlock(functionIndex, parentBlockId, depth);
        //push block stack
        blockStackForAFuncMeth.push(blockId);

        //visit children
        String str = "";
        if (ctx == null) {
            return str;
        }
        if(ctx.typeSwitchCase() != null) {
            str += visitTypeSwitchCase(ctx.typeSwitchCase());
        }
        str += ":";
        if(ctx.statementList() != null) {
            str += visitStatementList(ctx.statementList());
        }

        //pop block stack
        blockStackForAFuncMeth.pop();
        return str;
    }

    /**
     * selectStmt: 'select' '{' commClause* '}';
     * when entering, create a new select block and push blockStack, store this block.
     * when existing, pop blockStack.
     * @param ctx
     * @return
     */
    @Override
    public String visitSelectStmt(GolangParser.SelectStmtContext ctx) {
        //new block
        int parentBlockId = -1;
        if (!blockStackForAFuncMeth.isEmpty()) {
            parentBlockId = blockStackForAFuncMeth.peek();
        }
        int depth = blockStackForAFuncMeth.size();
        int blockId = processTask.processSelectBlock(functionIndex, parentBlockId, depth);
        //push block stack
        blockStackForAFuncMeth.push(blockId);

        //visit children
        String str = "select";
        if (ctx == null) {
            return str;
        }
        if (ctx.commClause() != null && !ctx.commClause().isEmpty()) {
            for (GolangParser.CommClauseContext commClauseContext : ctx.commClause()) {
                str += visitCommClause(commClauseContext);
            }
        }

        //pop block stack
        blockStackForAFuncMeth.pop();
        return str;
    }

    /**
     * commClause: commCase ':' statementList;
     * when entering, create a new select-clause block and push blockStack, store this block.
     * when existing, pop blockStack.
     * @param ctx
     * @return
     */
    @Override
    public String visitCommClause(GolangParser.CommClauseContext ctx) {
        //new block
        int parentBlockId = -1;
        if (!blockStackForAFuncMeth.isEmpty()) {
            parentBlockId = blockStackForAFuncMeth.peek();
        }
        int depth = blockStackForAFuncMeth.size();
        int blockId = processTask.processSelectCaseBlock(functionIndex, parentBlockId, depth);
        //push block stack
        blockStackForAFuncMeth.push(blockId);

        //visit children
        String str = "";
        if (ctx == null) {
            return str;
        }

        if(ctx.commCase() != null) {
            str += visitCommCase(ctx.commCase());
        }
        str += ":";
        if(ctx.statementList() != null) {
            str += visitStatementList(ctx.statementList());
        }
        //pop block stack
        blockStackForAFuncMeth.pop();
        return str;
    }


}//end class


