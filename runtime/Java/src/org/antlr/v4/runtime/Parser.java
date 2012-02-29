/*
 [The "BSD license"]
  Copyright (c) 2011 Terence Parr
  All rights reserved.

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions
  are met:

  1. Redistributions of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.
  2. Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.
  3. The name of the author may not be used to endorse or promote products
     derived from this software without specific prior written permission.

  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
  NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
  THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
  THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNSimulator;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.Nullable;
import org.antlr.v4.runtime.tree.ParseTreeListener;

import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.tree.ParseTree;

/** This is all the parsing support code essentially; most of it is error recovery stuff. */
public abstract class Parser<Symbol extends Token> extends Recognizer<Symbol, ParserATNSimulator<Symbol>> {
	public class TraceListener implements ParseTreeListener<Token> {
		@Override
		public void enterEveryRule(ParserRuleContext<? extends Token> ctx) {
			System.out.println("enter   " + getRuleNames()[ctx.ruleIndex] + ", LT(1)=" + _input.LT(1).getText());
		}

		@Override
		public void exitEveryRule(ParserRuleContext<? extends Token> ctx) {
			System.out.println("exit    "+getRuleNames()[ctx.ruleIndex]+", LT(1)="+_input.LT(1).getText());
		}

		@Override
		public void visitErrorNode(ParseTree.ErrorNode<? extends Token> node) {
			ParserRuleContext<?> parent = (ParserRuleContext<?>)node.getParent().getRuleContext();
			Token token = node.getSymbol();
			System.out.println("consume "+token+" rule "+
							   getRuleNames()[parent.ruleIndex]+
							   " alt="+parent.altNum);
		}

		@Override
		public void visitTerminal(ParseTree.TerminalNode<? extends Token> node) {
			ParserRuleContext<?> parent = (ParserRuleContext<?>)node.getParent().getRuleContext();
			Token token = node.getSymbol();
			System.out.println("consume "+token+" rule "+
							   getRuleNames()[parent.ruleIndex]+
							   " alt="+parent.altNum);
		}
	}

	public static class TrimToSizeListener implements ParseTreeListener<Token> {
		public static final TrimToSizeListener INSTANCE = new TrimToSizeListener();

		@Override
		public void visitTerminal(ParseTree.TerminalNode<? extends Token> node) {
		}

		@Override
		public void visitErrorNode(ParseTree.ErrorNode<? extends Token> node) {
		}

		@Override
		public void enterEveryRule(ParserRuleContext<? extends Token> ctx) {
		}

		@Override
		public void exitEveryRule(ParserRuleContext<? extends Token> ctx) {
			if (ctx.children instanceof ArrayList) {
				((ArrayList<?>)ctx.children).trimToSize();
			}
		}

	}

	protected ANTLRErrorStrategy<? super Symbol> _errHandler = new DefaultErrorStrategy<Symbol>();

	protected TokenStream<? extends Symbol> _input;

	/** The RuleContext object for the currently executing rule. This
	 *  must be non-null during parsing, but is initially null.
	 *  When somebody calls the start rule, this gets set to the
	 *  root context.
	 */
	protected ParserRuleContext<Symbol> _ctx;

	protected boolean _buildParseTrees;

	protected TraceListener _tracer;

	/** If the listener is non-null, trigger enter and exit rule events
     *  *during* the parse. This is typically done only when not building
     *  parse trees for later visiting. We either trigger events during
     *  the parse or during tree walks later. Both could be done.
     *  Not intended for tree parsing but would work.
     */
    protected List<ParseTreeListener<? super Symbol>> _parseListeners;

	/** Did the recognizer encounter a syntax error?  Track how many. */
	protected int _syntaxErrors = 0;

	public Parser(TokenStream<? extends Symbol> input) {
		setInputStream(input);
	}

	/** reset the parser's state */
	public void reset() {
		if ( getInputStream()!=null ) getInputStream().seek(0);
		_errHandler.endErrorCondition(this);
		_ctx = null;
		_syntaxErrors = 0;
		_tracer = null;
		ATNSimulator interpreter = getInterpreter();
		if (interpreter != null) {
			interpreter.reset();
		}
	}

	/** Match current input symbol against ttype.  Attempt
	 *  single token insertion or deletion error recovery.  If
	 *  that fails, throw MismatchedTokenException.
	 */
	public Symbol match(int ttype) throws RecognitionException {
		Symbol t = getCurrentToken();
		if ( getInputStream().LA(1)==ttype ) {
			_errHandler.endErrorCondition(this);
			consume();
		}
		else {
			t = _errHandler.recoverInline(this);
			if ( _buildParseTrees && t.getTokenIndex()==-1 ) {
				// we must have conjured up a new token during single token insertion
				// if it's not the current symbol
				_ctx.addErrorNode(t);
			}
		}
		return t;
	}

	/** Track the RuleContext objects during the parse and hook them up
	 *  using the children list so that it forms a parse tree.
	 *  The RuleContext returned from the start rule represents the root
	 *  of the parse tree.
	 *
	 *  To built parse trees, all we have to do is put a hook in setState()
	 *  and enterRule(). In setState(), we add tokens to the current context
	 *  as children. By the time we get to enterRule(), we are already
	 *  in an invoked rule so we add this context as a child of the parent
	 *  (invoking) context. Simple and effective.
	 *
	 *  Note that if we are not building parse trees, rule contexts
	 *  only point upwards. When a rule exits, it returns the context
	 *  but that gets garbage collected if nobody holds a reference.
	 *  It points upwards but nobody points at it.
	 *
	 *  When we build parse trees, we are adding all of these contexts to
	 *  somebody's children list. Contexts are then not candidates
	 *  for garbage collection.
	 */
	public void setBuildParseTree(boolean buildParseTrees) {
		this._buildParseTrees = buildParseTrees;
	}

	public boolean getBuildParseTree() {
		return _buildParseTrees;
	}

	/**
	 * Trim the internal lists of the parse tree during parsing to conserve memory.
	 * This property is set to {@code false} by default for a newly constructed parser.
	 *
	 * @param trimParseTrees {@code true} to trim the capacity of the {@link ParserRuleContext#children}
	 * list to its size after a rule is parsed.
	 */
	public void setTrimParseTrees(boolean trimParseTrees) {
		if (trimParseTrees) {
			if (getTrimParseTrees()) {
				return;
			}

			addParseListener(TrimToSizeListener.INSTANCE);
		}
		else {
			removeParseListener(TrimToSizeListener.INSTANCE);
		}
	}

	/**
	 *
	 * @return {@code true} if the {@link ParserRuleContext#children} list is trimmed
	 * using the default {@link Parser.TrimToSizeListener} during the parse process.
	 */
	public boolean getTrimParseTrees() {
		if (_parseListeners == null) {
			return false;
		}

		return _parseListeners.contains(TrimToSizeListener.INSTANCE);
	}

//	public void setTraceATNStates(boolean traceATNStates) {
//		this.traceATNStates = traceATNStates;
//	}
//
//	public boolean getTraceATNStates() {
//		return traceATNStates;
//	}

    public List<ParseTreeListener<? super Symbol>> getParseListeners() {
        return _parseListeners;
    }

    public void addParseListener(ParseTreeListener<? super Symbol> listener) {
		if ( listener==null ) return;
		if ( _parseListeners==null ) {
			_parseListeners = new ArrayList<ParseTreeListener<? super Symbol>>();
		}
        this._parseListeners.add(listener);
    }

	public void removeParseListener(ParseTreeListener<? super Symbol> l) {
		if ( l==null ) return;
		if ( _parseListeners!=null ) {
			_parseListeners.remove(l);
			if (_parseListeners.isEmpty()) {
				_parseListeners = null;
			}
		}
	}

	public void removeParseListeners() {
		_parseListeners = null;
	}

	public void triggerEnterRuleEvent() {
		for (ParseTreeListener<? super Symbol> l : _parseListeners) {
			l.enterEveryRule(_ctx);
			_ctx.enterRule(l);
		}
	}

	public void triggerExitRuleEvent() {
		// reverse order walk of listeners
		for (int i = _parseListeners.size()-1; i >= 0; i--) {
			ParseTreeListener<? super Symbol> l = _parseListeners.get(i);
			_ctx.exitRule(l);
			l.exitEveryRule(_ctx);
		}
	}

    /** Get number of recognition errors (lexer, parser, tree parser).  Each
	 *  recognizer tracks its own number.  So parser and lexer each have
	 *  separate count.  Does not count the spurious errors found between
	 *  an error and next valid token match
	 *
	 *  See also reportError()
	 */
	public int getNumberOfSyntaxErrors() {
		return _syntaxErrors;
	}

	public ANTLRErrorStrategy<? super Symbol> getErrorHandler() {
		return _errHandler;
	}

	public void setErrorHandler(ANTLRErrorStrategy<? super Symbol> handler) {
		this._errHandler = handler;
	}

	@Override
	public TokenStream<? extends Symbol> getInputStream() { return getTokenStream(); }

	@Override
	public final void setInputStream(IntStream<? extends Symbol> input) {
		setTokenStream((TokenStream<? extends Symbol>)input);
	}

	public TokenStream<? extends Symbol> getTokenStream() {
		return _input;
	}

	/** Set the token stream and reset the parser */
	public void setTokenStream(TokenStream<? extends Symbol> input) {
		this._input = null;
		reset();
		this._input = input;
	}

    public String getInputString(int start) {
        return getInputString(start, getInputStream().index());
    }

    public String getInputString(int start, int stop) {
        SymbolStream<? extends Symbol> input = getInputStream();
        if ( input instanceof TokenStream<?> ) {
            return ((TokenStream<? extends Symbol>)input).toString(start,stop);
        }
        return "n/a";
    }

    /** Match needs to return the current input symbol, which gets put
     *  into the label for the associated token ref; e.g., x=ID.
     */
    public Symbol getCurrentToken() {
		return _input.LT(1);
	}

    public void notifyErrorListeners(String msg)	{
		notifyErrorListeners(getCurrentToken(), msg, null);
	}

	public void notifyErrorListeners(Symbol offendingToken, String msg,
									 @Nullable RecognitionException e)
	{
		int line = -1;
		int charPositionInLine = -1;
		if (offendingToken != null) {
			line = offendingToken.getLine();
			charPositionInLine = offendingToken.getCharPositionInLine();
		}
		List<? extends ANTLRErrorListener<? super Symbol>> listeners = getErrorListeners();
		if ( listeners.isEmpty() ) {
			System.err.println("line "+line+":"+charPositionInLine+" "+msg);
			return;
		}
		for (ANTLRErrorListener<? super Symbol> pl : listeners) {
			pl.error(this, offendingToken, line, charPositionInLine, msg, e);
		}
	}

	/** Consume the current symbol and return it. E.g., given the following
	 *  input with A being the current lookahead symbol:
	 *
	 *  	A B
	 *  	^
	 *
	 *  this function moves the cursor to B and returns A.
	 *
	 *  If the parser is creating parse trees, the current symbol
	 *  would also be added as a child to the current context (node).
     *
     *  Trigger listener events if there's a listener.
	 */
	public Symbol consume() {
		Symbol o = getCurrentToken();
		getInputStream().consume();
		boolean hasListener = _parseListeners != null && !_parseListeners.isEmpty();
		if (_buildParseTrees || hasListener) {
			// TODO: tree parsers?
			if ( _errHandler.inErrorRecoveryMode(this) ) {
//				System.out.println("consume in error recovery mode for "+o);
				ParseTree.ErrorNode<Symbol> node = _ctx.addErrorNode(o);
				if (_parseListeners != null) {
					for (ParseTreeListener<? super Symbol> listener : _parseListeners) {
						listener.visitErrorNode(node);
					}
				}
			}
			else {
				ParseTree.TerminalNode<Symbol> node = _ctx.addChild(o);
				if (_parseListeners != null) {
					for (ParseTreeListener<? super Symbol> listener : _parseListeners) {
						listener.visitTerminal(node);
					}
				}
			}
		}
		return o;
	}

	protected void addContextToParseTree() {
		ParserRuleContext<Symbol> parent = (ParserRuleContext<Symbol>)_ctx.parent;
		// add current context to parent if we have a parent
		if ( parent!=null )	{
			parent.addChild(_ctx);
		}
	}

	/** Always called by generated parsers upon entry to a rule.
	 *  This occurs after the new context has been pushed. Access field
	 *  _ctx get the current context.
	 *
	 *  This is flexible because users do not have to regenerate parsers
	 *  to get trace facilities.
	 */
	public void enterRule(ParserRuleContext<Symbol> localctx, int ruleIndex) {
		_ctx = localctx;
		_ctx.start = _input.LT(1);
		_ctx.ruleIndex = ruleIndex;
		if (_buildParseTrees) addContextToParseTree();
        if ( _parseListeners != null) triggerEnterRuleEvent();
	}

    public void exitRule() {
        // trigger event on _ctx, before it reverts to parent
        if ( _parseListeners != null) triggerExitRuleEvent();
		_ctx = (ParserRuleContext<Symbol>)_ctx.parent;
    }

	public void enterOuterAlt(ParserRuleContext<Symbol> localctx, int altNum) {
		// if we have new localctx, make sure we replace existing ctx
		// that is previous child of parse tree
		if ( _buildParseTrees && _ctx != localctx ) {
			ParserRuleContext<Symbol> parent = (ParserRuleContext<Symbol>)_ctx.parent;
			parent.removeLastChild();
			if ( parent!=null )	parent.addChild(localctx);
		}
		_ctx = localctx;
		_ctx.altNum = altNum;
	}

	/* like enterRule but for recursive rules; no enter events for recursive rules. */
	public void pushNewRecursionContext(ParserRuleContext<Symbol> localctx, int ruleIndex) {
		_ctx = localctx;
		_ctx.start = _input.LT(1);
		_ctx.ruleIndex = ruleIndex;
	}

	public void unrollRecursionContexts(ParserRuleContext<Symbol> _parentctx) {
		ParserRuleContext<Symbol> retctx = _ctx; // save current ctx (return value)

		// unroll so _ctx is as it was before call to recursive method
		if ( _parseListeners != null ) {
			while ( _ctx != _parentctx ) {
				triggerExitRuleEvent();
				_ctx = (ParserRuleContext<Symbol>)_ctx.parent;
			}
		}
		else {
			_ctx = _parentctx;
		}
		// hook into tree
		retctx.parent = _parentctx;
		if (_buildParseTrees) _parentctx.addChild(retctx); // add return ctx into invoking rule's tree
	}

	public ParserRuleContext<Symbol> getInvokingContext(int ruleIndex) {
		ParserRuleContext<Symbol> p = _ctx;
		while ( p!=null ) {
			if ( p.getRuleIndex() == ruleIndex ) return p;
			p = (ParserRuleContext<Symbol>)p.parent;
		}
		return null;
	}

	public ParserRuleContext<Symbol> getContext() {
		return _ctx;
	}

	public boolean inContext(String context) {
		// TODO: useful in parser?
		return false;
	}

    public boolean isExpectedToken(int symbol) {
//   		return getInterpreter().atn.nextTokens(_ctx);
        ATN atn = getInterpreter().atn;
		ParserRuleContext<?> ctx = _ctx;
        ATNState s = atn.states.get(ctx.s);
        IntervalSet following = atn.nextTokens(s);
        if (following.contains(symbol)) {
            return true;
        }
//        System.out.println("following "+s+"="+following);
        if ( !following.contains(Token.EPSILON) ) return false;

        while ( ctx!=null && ctx.invokingState>=0 && following.contains(Token.EPSILON) ) {
            ATNState invokingState = atn.states.get(ctx.invokingState);
            RuleTransition rt = (RuleTransition)invokingState.transition(0);
            following = atn.nextTokens(rt.followState);
            if (following.contains(symbol)) {
                return true;
            }

            ctx = (ParserRuleContext<?>)ctx.parent;
        }

        if ( following.contains(Token.EPSILON) && symbol == Token.EOF ) {
            return true;
        }

        return false;
    }

	/** Compute the set of valid tokens reachable from the current
	 *  position in the parse.
	 */
    public IntervalSet getExpectedTokens() {
        ATN atn = getInterpreter().atn;
		ParserRuleContext<?> ctx = _ctx;
        ATNState s = atn.states.get(ctx.s);
        IntervalSet following = atn.nextTokens(s);
//        System.out.println("following "+s+"="+following);
        if ( !following.contains(Token.EPSILON) ) return following;
        IntervalSet expected = new IntervalSet();
        expected.addAll(following);
        expected.remove(Token.EPSILON);
        while ( ctx!=null && ctx.invokingState>=0 && following.contains(Token.EPSILON) ) {
            ATNState invokingState = atn.states.get(ctx.invokingState);
            RuleTransition rt = (RuleTransition)invokingState.transition(0);
            following = atn.nextTokens(rt.followState);
            expected.addAll(following);
            expected.remove(Token.EPSILON);
            ctx = (ParserRuleContext<?>)ctx.parent;
        }
        if ( following.contains(Token.EPSILON) ) {
            expected.add(Token.EOF);
        }
        return expected;
   	}

    public IntervalSet getExpectedTokensWithinCurrentRule() {
        ATN atn = getInterpreter().atn;
        ATNState s = atn.states.get(_ctx.s);
   		return atn.nextTokens(s);
   	}

//	/** Compute the set of valid tokens reachable from the current
//	 *  position in the parse.
//	 */
//	public IntervalSet nextTokens(@NotNull RuleContext ctx) {
//		ATN atn = getInterpreter().atn;
//		ATNState s = atn.states.get(ctx.s);
//		if ( s == null ) return null;
//		return atn.nextTokens(s, ctx);
//	}

	public ParserRuleContext<Symbol> getRuleContext() { return _ctx; }

	/** Return List<String> of the rule names in your parser instance
	 *  leading up to a call to the current rule.  You could override if
	 *  you want more details such as the file/line info of where
	 *  in the ATN a rule is invoked.
	 *
	 *  This is very useful for error messages.
	 */
	public List<String> getRuleInvocationStack() {
		return getRuleInvocationStack(_ctx);
	}

	public List<String> getRuleInvocationStack(RuleContext<?> p) {
		String[] ruleNames = getRuleNames();
		List<String> stack = new ArrayList<String>();
		while ( p!=null ) {
			// compute what follows who invoked us
			stack.add(ruleNames[p.getRuleIndex()]);
			p = p.parent;
		}
		return stack;
	}

    /** For debugging and other purposes */
    public List<String> getDFAStrings() {
        List<String> s = new ArrayList<String>();
        for (int d = 0; d < _interp.decisionToDFA.length; d++) {
            DFA dfa = _interp.decisionToDFA[d];
            s.add( dfa.toString(getTokenNames(), getRuleNames()) );
        }
        return s;
    }

    /** For debugging and other purposes */
    public void dumpDFA() {
        boolean seenOne = false;
        for (int d = 0; d < _interp.decisionToDFA.length; d++) {
            DFA dfa = _interp.decisionToDFA[d];
            if ( dfa!=null ) {
                if ( seenOne ) System.out.println();
                System.out.println("Decision " + dfa.decision + ":");
                System.out.print(dfa.toString(getTokenNames(), getRuleNames()));
                seenOne = true;
            }
        }
    }

	public String getSourceName() {
		return _input.getSourceName();
	}

	/** A convenience method for use most often with template rewrites.
	 *  Convert a List<Token> to List<String>
	 */
	public List<String> toStrings(List<? extends Token> tokens) {
		if ( tokens==null ) return null;
		List<String> strings = new ArrayList<String>(tokens.size());
		for (int i=0; i<tokens.size(); i++) {
			strings.add(tokens.get(i).getText());
		}
		return strings;
	}

	/** Indicate that the recognizer has changed internal state that is
	 *  consistent with the ATN state passed in.  This way we always know
	 *  where we are in the ATN as the parser goes along. The rule
	 *  context objects form a stack that lets us see the stack of
	 *  invoking rules. Combine this and we have complete ATN
	 *  configuration information.
	 */
	public void setState(int atnState) {
//		System.err.println("setState "+atnState);
		_ctx.s = atnState;
//		if ( traceATNStates ) _ctx.trace(atnState);
	}

	/** During a parse is extremely useful to listen in on the rule entry and exit
	 *  events as well as token matches. This is for quick and dirty debugging.
	 */
	public void setTrace(boolean trace) {
		if ( !trace ) {
			removeParseListener(_tracer);
			_tracer = null;
		}
		else {
			if ( _tracer!=null ) removeParseListener(_tracer);
			else _tracer = new TraceListener();
			addParseListener(_tracer);
		}
	}
}
