// Stanford Parser -- a probabilistic lexicalized NL CFG parser
// Copyright (c) 2002, 2003, 2004, 2005 The Board of Trustees of
// The Leland Stanford Junior University. All Rights Reserved.
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 2
// of the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
//
// For more information, bug reports, fixes, contact:
//    Christopher Manning
//    Dept of Computer Science, Gates 1A
//    Stanford CA 94305-9010
//    USA
//    parser-support@lists.stanford.edu
//    http://nlp.stanford.edu/downloads/lex-parser.shtml

package edu.stanford.nlp.parser.lexparser;

import edu.stanford.nlp.io.EncodingPrintWriter;
import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.math.SloppyMath;
import edu.stanford.nlp.parser.KBestViterbiParser;
import edu.stanford.nlp.trees.TreeFactory;
import edu.stanford.nlp.trees.TreebankLanguagePack;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.LabeledScoredTreeFactory;
import edu.stanford.nlp.util.*;
import edu.stanford.nlp.util.PriorityQueue;

import java.util.*;
import java.util.regex.Matcher;

/** An exhaustive generalized CKY PCFG parser.
 *  Fairly carefully optimized to be fast.
 *
 *  @author Dan Klein
 *  @author Christopher Manning (I seem to maintain it....)
 *  @author Jenny Finkel (N-best and sampling code, former from Liang/Chiang)
 */
public class ExhaustivePCFGParser implements Scorer, KBestViterbiParser {

  // public static long insideTime = 0;  // for profiling
  // public static long outsideTime = 0;

  protected String goalStr;
  protected String stateSpace;
  protected Numberer stateNumberer;
  protected Numberer wordNumberer = Numberer.getGlobalNumberer("words");
  protected Numberer tagNumberer = Numberer.getGlobalNumberer("tags");

  protected TreeFactory tf;

  protected BinaryGrammar bg;
  protected UnaryGrammar ug;
  protected Lexicon lex;
  protected Options op;
  protected TreebankLanguagePack tlp;

  protected OutsideRuleFilter orf;

  // inside scores
  protected float[][][] iScore;  // start idx, end idx, state -> logProb
  // outside scores
  protected float[][][] oScore;  // start idx, end idx, state -> logProb
  protected float bestScore;

  protected int[][][] wordsInSpan; // number of words in span with this state

  protected boolean[][] oFilteredStart;
  protected boolean[][] oFilteredEnd;

  protected boolean[][] iPossibleByL;
  protected boolean[][] iPossibleByR;
  protected boolean[][] oPossibleByL;
  protected boolean[][] oPossibleByR;

  protected int[] words;  // words of sentence being parsed as word Numberer ints
  protected IntPair[] offsets;
  protected int length;
  protected boolean[][] tags;
  protected int myMaxLength = -0xDEADBEEF;

  protected int numStates;
  protected int arraySize = 0;

  public void setGoalString(String goalStr) {
    this.goalStr = goalStr;
  }

  public double oScore(Edge edge) {
    double oS = oScore[edge.start][edge.end][edge.state];
    if (Test.pcfgThreshold) {
      double iS = iScore[edge.start][edge.end][edge.state];
      if (iS + oS - bestScore < Test.pcfgThresholdValue) {
        return Double.NEGATIVE_INFINITY;
      }
    }
    return oS;
  }

  public double iScore(Edge edge) {
    return iScore[edge.start][edge.end][edge.state];
  }

  public boolean oPossible(Hook hook) {
    return (hook.isPreHook() ? oPossibleByR[hook.end][hook.state] : oPossibleByL[hook.start][hook.state]);
  }

  public boolean iPossible(Hook hook) {
    return (hook.isPreHook() ? iPossibleByR[hook.start][hook.subState] : iPossibleByL[hook.end][hook.subState]);
  }


  public boolean oPossibleL(int state, int start) {
    return oPossibleByL[start][state];
  }

  public boolean oPossibleR(int state, int end) {
    return oPossibleByR[end][state];
  }

  public boolean iPossibleL(int state, int start) {
    return iPossibleByL[start][state];
  }

  public boolean iPossibleR(int state, int end) {
    return iPossibleByR[end][state];
  }

  protected void buildOFilter() {
    orf.init();
    for (int start = 0; start < length; start++) {
      orf.leftAccepting(oFilteredStart[start]);
      orf.advanceRight(tags[start]);
    }
    for (int end = length; end > 0; end--) {
      orf.rightAccepting(oFilteredEnd[end]);
      orf.advanceLeft(tags[end - 1]);
    }
  }


  public double validateBinarizedTree(Tree tree, int start) {
    if (tree.isLeaf()) {
      return 0.0;
    }
    float epsilon = 0.0001f;
    if (tree.isPreTerminal()) {
      int tag = Numberer.number("tags", tree.label().value());
      int word = Numberer.number("words", tree.children()[0].label().value());
      IntTaggedWord iTW = new IntTaggedWord(word, tag);
      float score = lex.score(iTW, start);
      float bound = iScore[start][start + 1][Numberer.number(stateSpace, tree.label().value())];
      if (score > bound + epsilon) {
        System.out.println("Invalid tagging:");
        System.out.println("  Tag: " + tree.label().value());
        System.out.println("  Word: " + tree.children()[0].label().value());
        System.out.println("  Score: " + score);
        System.out.println("  Bound: " + bound);
      }
      return score;
    }
    int parent = Numberer.number(stateSpace, tree.label().value());
    int firstChild = Numberer.number(stateSpace, tree.children()[0].label().value());
    if (tree.numChildren() == 1) {
      UnaryRule ur = new UnaryRule();
      ur.parent = parent;
      ur.child = firstChild;
      double score = SloppyMath.max(ug.scoreRule(ur), -10000.0) + validateBinarizedTree(tree.children()[0], start);
      double bound = iScore[start][start + tree.yield().size()][parent];
      if (score > bound + epsilon) {
        System.out.println("Invalid unary:");
        System.out.println("  Parent: " + tree.label().value());
        System.out.println("  Child: " + tree.children()[0].label().value());
        System.out.println("  Start: " + start);
        System.out.println("  End: " + (start + tree.yield().size()));
        System.out.println("  Score: " + score);
        System.out.println("  Bound: " + bound);
      }
      return score;
    }
    BinaryRule br = new BinaryRule();
    br.parent = parent;
    br.leftChild = firstChild;
    br.rightChild = Numberer.number(stateSpace, tree.children()[1].label().value());
    double score = SloppyMath.max(bg.scoreRule(br), -10000.0) + validateBinarizedTree(tree.children()[0], start) + validateBinarizedTree(tree.children()[1], start + tree.children()[0].yield().size());
    double bound = iScore[start][start + tree.yield().size()][parent];
    if (score > bound + epsilon) {
      System.out.println("Invalid binary:");
      System.out.println("  Parent: " + tree.label().value());
      System.out.println("  LChild: " + tree.children()[0].label().value());
      System.out.println("  RChild: " + tree.children()[1].label().value());
      System.out.println("  Start: " + start);
      System.out.println("  End: " + (start + tree.yield().size()));
      System.out.println("  Score: " + score);
      System.out.println("  Bound: " + bound);
    }
    return score;
  }

  // needs to be set up so that uses same Train options...
  public Tree scoreNonBinarizedTree(Tree tree) {
    TreeAnnotatorAndBinarizer binarizer = new TreeAnnotatorAndBinarizer(op.tlpParams, op.forceCNF, !Train.outsideFactor(), true);
    tree = binarizer.transformTree(tree);
    scoreBinarizedTree(tree, 0);
    return op.tlpParams.subcategoryStripper().transformTree(new Debinarizer(op.forceCNF).transformTree(tree));
    //    return debinarizer.transformTree(t);
  }

  //
  public double scoreBinarizedTree(Tree tree, int start) {
    if (tree.isLeaf()) {
      return 0.0;
    }
    if (tree.isPreTerminal()) {
      int tag = Numberer.number("tags", tree.label().value());
      int word = Numberer.number("words", tree.children()[0].label().value());
      IntTaggedWord iTW = new IntTaggedWord(word, tag);
      // if (lex.score(iTW,(leftmost ? 0 : 1)) == Double.NEGATIVE_INFINITY) {
      //   System.out.println("NO SCORE FOR: "+iTW);
      // }
      float score = lex.score(iTW, start);
      tree.setScore(score);
      return score;
    }
    int parent = Numberer.number(stateSpace, tree.label().value());
    int firstChild = Numberer.number(stateSpace, tree.children()[0].label().value());
    if (tree.numChildren() == 1) {
      UnaryRule ur = new UnaryRule();
      ur.parent = parent;
      ur.child = firstChild;
      //+ DEBUG
      // if (ug.scoreRule(ur) < -10000) {
      //        System.out.println("Grammar doesn't have rule: " + ur);
      // }
      //      return SloppyMath.max(ug.scoreRule(ur), -10000.0) + scoreBinarizedTree(tree.children()[0], leftmost);
      double score = ug.scoreRule(ur) + scoreBinarizedTree(tree.children()[0], start);
      tree.setScore(score);
      return score;
    }
    BinaryRule br = new BinaryRule();
    br.parent = parent;
    br.leftChild = firstChild;
    br.rightChild = Numberer.number(stateSpace, tree.children()[1].label().value());
    //+ DEBUG
    // if (bg.scoreRule(br) < -10000) {
    //  System.out.println("Grammar doesn't have rule: " + br);
    // }
    //    return SloppyMath.max(bg.scoreRule(br), -10000.0) +
    //            scoreBinarizedTree(tree.children()[0], leftmost) +
    //            scoreBinarizedTree(tree.children()[1], false);
    double score = bg.scoreRule(br) + scoreBinarizedTree(tree.children()[0], start) + scoreBinarizedTree(tree.children()[1], start + tree.children()[0].yield().size());
    tree.setScore(score);
    return score;
  }


  protected static final boolean spillGuts = false;
  protected static final boolean dumpTagging = false;
  private static long time = System.currentTimeMillis();

  protected static void tick(String str) {
    long time2 = System.currentTimeMillis();
    long diff = time2 - time;
    time = time2;
    System.err.print("done.  " + diff + "\n" + str);
  }

  protected boolean floodTags = false;
  protected List sentence = null;
  protected Lattice lr = null;

  protected int[][] narrowLExtent = null; // the rightmost left extent of state s ending at position i
  protected int[][] wideLExtent = null; // the leftmost left extent of state s ending at position i
  protected int[][] narrowRExtent = null; // the leftmost right extent of state s starting at position i
  protected int[][] wideRExtent = null; // the rightmost right extent of state s starting at position i

  protected boolean[] isTag = null;

  /** Just for Parser interface.  Remove it when we change Parser interface to use
   *  List<HasWord>
   * @param sentence The sentence to parse
   * @param goal The goal, presently ignored
   * @return Whether it can be parsed by the grammar
   */
  public boolean parse(List<? extends HasWord> sentence, String goal) {
    return parse(sentence);
  }

  public boolean parse(List<? extends HasWord> sentence) { // ExhaustivePCFGParser
    lr = null; // better nullPointer exception than silent error
    //System.out.println("is it a taggedword?" + (sentence.get(0) instanceof TaggedWord)); //debugging
    if (sentence != this.sentence) {
      this.sentence = sentence;
      floodTags = false;
    }
    if (Test.verbose) {
      Timing.tick("Starting pcfg parse.");
    }
    if (spillGuts) {
      tick("Starting PCFG parse...");
    }
    length = sentence.size();
    if (length > arraySize) {
      considerCreatingArrays(length);
    }
    int goal = stateNumberer.number(goalStr);
    if (Test.verbose) {
      // System.out.println(numStates + " states, " + goal + " is the goal state.");
      // System.err.println(new ArrayList(ug.coreRules.keySet()));
      System.err.print("Initializing PCFG...");
    }
    // map input words to words array (wordNumberer ints)
    words = new int[length];
    offsets = new IntPair[length];
    int unk = 0;
    StringBuilder unkWords = new StringBuilder("[");
    for (int i = 0; i < length; i++) {
      Object o = sentence.get(i);
      if (o instanceof HasOffset) {
        offsets[i] = new IntPair(
              ((HasOffset) o).beginPosition(),
              ((HasOffset) o).endPosition());
      }
      if (o instanceof HasWord) {
        o = ((HasWord) o).word();
      }
      String s = o.toString();
      if (Test.verbose && ! lex.isKnown(wordNumberer.number(s))) {
        unk++;
        unkWords.append(" ");
        unkWords.append(s);
        unkWords.append(" { ");
        for (int jj = 0; jj < s.length(); jj++) {
          char ch = s.charAt(jj);
          unkWords.append(Character.getType(ch)).append(" ");
        }
        unkWords.append("}");
      }
      words[i] = wordNumberer.number(s);
      //else
      //      words[i] = wordNumberer.number(Lexicon.UNKNOWN_WORD);
    }

    // initialize inside and outside score arrays
    if (spillGuts) {
      tick("Wiping arrays...");
    }
    for (int start = 0; start < length; start++) {
      for (int end = start + 1; end <= length; end++) {
        Arrays.fill(iScore[start][end], Float.NEGATIVE_INFINITY);
        if (op.doDep && ! Test.useFastFactored) {
          Arrays.fill(oScore[start][end], Float.NEGATIVE_INFINITY);
        }
        if (Test.lengthNormalization) {
          Arrays.fill(wordsInSpan[start][end], 1);
        }
      }
    }
    for (int loc = 0; loc <= length; loc++) {
      Arrays.fill(narrowLExtent[loc], -1); // the rightmost left with state s ending at i that we can get is the beginning
      Arrays.fill(wideLExtent[loc], length + 1); // the leftmost left with state s ending at i that we can get is the end
      Arrays.fill(narrowRExtent[loc], length + 1); // the leftmost right with state s starting at i that we can get is the end
      Arrays.fill(wideRExtent[loc], -1); // the rightmost right with state s starting at i that we can get is the beginning
    }
    // int puncTag = stateNumberer.number(".");
    // boolean lastIsPunc = false;
    if (Test.verbose) {
      Timing.tick("done.");
      unkWords.append(" ]");
      op.tlpParams.pw(System.err).println("Unknown words: " + unk + " " + unkWords);
      System.err.print("Starting filters...");
    }
    // do tags
    if (spillGuts) {
      tick("Tagging...");
    }
    initializeChart(sentence);
    //if (Test.outsideFilter)
    // buildOFilter();
    if (Test.verbose) {
      Timing.tick("done.");
      System.err.print("Starting insides...");
    }
    // do the inside probabilities
    doInsideScores();
    if (Test.verbose) {
      // insideTime += Timing.tick("done.");
      Timing.tick("done.");
      System.out.println("PCFG parsing " + length + " words (incl. stop): insideScore = " + iScore[0][length][goal]);
    }
    bestScore = iScore[0][length][goal];
    boolean succeeded = hasParse();
    if (Test.doRecovery && !succeeded && !floodTags) {
      floodTags = true; // sentence will try to reparse
      // ms: disabled message. this is annoying and it doesn't really provide much information
      // System.err.println("Trying recovery parse...");
      return parse(sentence);
    }
    if ( ! op.doDep || Test.useFastFactored) {
      return succeeded;
    }
    if (Test.verbose) {
      System.err.print("Starting outsides...");
    }
    // outside scores
    oScore[0][length][goal] = 0.0f;
    doOutsideScores();
    //System.out.println("State rate: "+((int)(1000*ohits/otries))/10.0);
    //System.out.println("Traversals: "+ohits);
    if (Test.verbose) {
      // outsideTime += Timing.tick("Done.");
      Timing.tick("done.");
      System.err.print("Starting half-filters...");
    }
    for (int loc = 0; loc <= length; loc++) {
      Arrays.fill(iPossibleByL[loc], false);
      Arrays.fill(iPossibleByR[loc], false);
      Arrays.fill(oPossibleByL[loc], false);
      Arrays.fill(oPossibleByR[loc], false);
    }
    for (int start = 0; start < length; start++) {
      for (int end = start + 1; end <= length; end++) {
        for (int state = 0; state < numStates; state++) {
          if (iScore[start][end][state] > Float.NEGATIVE_INFINITY && oScore[start][end][state] > Float.NEGATIVE_INFINITY) {
            iPossibleByL[start][state] = true;
            iPossibleByR[end][state] = true;
            oPossibleByL[start][state] = true;
            oPossibleByR[end][state] = true;
          }
        }
      }
    }
    if (Test.verbose) {
      Timing.tick("done.");
    }
    if (false) {
      long iNZ = 0;
      long oNZ = 0;
      long bNZ = 0;
      long tot = 0;
      for (int start = 0; start < length; start++) {
        for (int end = start + 1; end <= length; end++) {
          for (int s = 0; s < numStates; s++) {
            tot++;
            if (iScore[start][end][s] != Float.NEGATIVE_INFINITY) {
              iNZ++;
            }
            if (oScore[start][end][s] != Float.NEGATIVE_INFINITY) {
              oNZ++;
            }
            if (oScore[start][end][s] != Float.NEGATIVE_INFINITY && iScore[start][end][s] != Float.NEGATIVE_INFINITY) {
              bNZ++;
            }
          }
        }
      }
      System.out.println("Zero Saturation (i): " + ((int) (1000 * iNZ / tot)) / 10.0);
      System.out.println("Zero Saturation (o): " + ((int) (1000 * oNZ / tot)) / 10.0);
      System.out.println("Zero Saturation (b): " + ((int) (1000 * bNZ / tot)) / 10.0);
      Timing.tick("Done with zero scan.");
    }
    return succeeded;
  }

  public boolean parse(HTKLatticeReader lr) {
    //TODO wsg 20-jan-2010
    // There are presently 2 issues with HTK lattice parsing:
    //   (1) The initializeChart() method present in rev. 19820 did not properly initialize
    //         lattices (or sub-lattices) like this (where A,B,C are nodes, and NN is the POS tag arc label):
    //
    //              --NN--> B --NN--
    //             /                \
    //            A ------NN-------> C
    //
    //   (2) extractBestParse() was not implemented properly.
    //
    //   To re-implement support for HTKLatticeReader it is necessary to create an interface
    //   for the two different lattice implementations and then modify initializeChart() and
    //   extractBestParse() as appropriate. Another solution would be to duplicate these two
    //   methods and make the necessary changes for HTKLatticeReader. In both cases, the
    //   acoustic model score provided by the HTK lattices should be included in the weighting.
    //
    //   Note that I never actually tested HTKLatticeReader, so I am uncertain if this facility
    //   actually worked in the first place.
    //
    System.err.printf("%s: HTK lattice parsing presently disabled.\n", this.getClass().getName());
    return false;
  }
  
  public boolean parse(Lattice lr) { // ExhaustivePCFGParser
    sentence = null; // better nullPointer exception than silent error
    if (lr != this.lr) {
      this.lr = lr;
      floodTags = false;
    }

    if (Test.verbose)
      Timing.tick("Doing lattice PCFG parse...");
    
    
    // The number of whitespace nodes in the lattice
    length = lr.getNumNodes() - 1; //Subtract 1 since considerCreatingArrays will add the final interstice
    if (length > arraySize)
      considerCreatingArrays(length);
    
    
    int goal = stateNumberer.number(goalStr);
//    if (Test.verbose) {
//      System.err.println("Unaries: " + ug.rules());
//      System.err.println("Binaries: " + bg.rules());
//      System.err.println("Initializing PCFG...");
//      System.err.println("   " + numStates + " states, " + goal + " is the goal state.");
//    }
    
//    System.err.println("Tagging states");
//    for(int i = 0; i < numStates; i++) {
//      if(isTag[i]) {
//        int tagId = Numberer.translate(stateSpace, "tags", i);
//        String tag = (String) tagNumberer.object(tagId);
//        System.err.printf(" %d: %s\n",i,tag);
//      }
//    }
    
    // Create a map of all words in the lattice
    //
//    int numEdges = lr.getNumEdges();
//    words = new int[numEdges];
//    offsets = new IntPair[numEdges];
//    
//    int unk = 0;
//    int i = 0;
//    StringBuilder unkWords = new StringBuilder("[");
//    for (LatticeEdge edge : lr) {
//      String s = edge.word;
//      if (Test.verbose && !lex.isKnown(wordNumberer.number(s))) {
//        unk++;
//        unkWords.append(" " + s);
//      }
//      words[i++] = wordNumberer.number(s);
//    }
    
    for (int start = 0; start < length; start++)
      for (int end = start + 1; end <= length; end++)
        Arrays.fill(iScore[start][end], Float.NEGATIVE_INFINITY);
   
    
    for (int loc = 0; loc <= length; loc++) {
      Arrays.fill(narrowLExtent[loc], -1); // the rightmost left with state s ending at i that we can get is the beginning
      Arrays.fill(wideLExtent[loc], length + 1); // the leftmost left with state s ending at i that we can get is the end
      Arrays.fill(narrowRExtent[loc], length + 1); // the leftmost right with state s starting at i that we can get is the end
      Arrays.fill(wideRExtent[loc], -1); // the rightmost right with state s starting at i that we can get is the beginning
    }
    
    initializeChart(lr);
    
    doInsideScores();
    bestScore = iScore[0][length][goal];
    
    if (Test.verbose) {
      Timing.tick("done.");
      System.err.println("PCFG " + length + " words (incl. stop) iScore " + bestScore);
    }
    
    boolean succeeded = hasParse();
    
    // Try a recovery parse
    if (!succeeded && Test.doRecovery && !floodTags) {
      floodTags = true;
      // ms: disabled message. this is annoying and it doesn't really provide much information
      // System.err.printf("Trying recovery parse...");
      succeeded = parse(lr);
    }
        
    return succeeded;
  }

  private void doOutsideScores() {
    for (int diff = length; diff >= 1; diff--) {
      for (int start = 0; start + diff <= length; start++) {
        int end = start + diff;
        // do unaries
        for (int s = 0; s < numStates; s++) {
          float oS = oScore[start][end][s];
          if (oS == Float.NEGATIVE_INFINITY) {
            continue;
          }
          UnaryRule[] rules = ug.closedRulesByParent(s);
          for (UnaryRule ur : rules) {
            float pS = ur.score;
            float tot = oS + pS;
            if (tot > oScore[start][end][ur.child] && iScore[start][end][ur.child] > Float.NEGATIVE_INFINITY) {
              oScore[start][end][ur.child] = tot;
            }
          }
        }
        // do binaries
        for (int s = 0; s < numStates; s++) {
          int min1 = narrowRExtent[start][s];
          if (end < min1) {
            continue;
          }
          BinaryRule[] rules = bg.splitRulesWithLC(s);
          for (BinaryRule br  : rules) {
            float oS = oScore[start][end][br.parent];
            if (oS == Float.NEGATIVE_INFINITY) {
              continue;
            }
            int max1 = narrowLExtent[end][br.rightChild];
            if (max1 < min1) {
              continue;
            }
            int min = min1;
            int max = max1;
            if (max - min > 2) {
              int min2 = wideLExtent[end][br.rightChild];
              min = (min1 > min2 ? min1 : min2);
              if (max1 < min) {
                continue;
              }
              int max2 = wideRExtent[start][br.leftChild];
              max = (max1 < max2 ? max1 : max2);
              if (max < min) {
                continue;
              }
            }
            float pS = br.score;
            for (int split = min; split <= max; split++) {
              float lS = iScore[start][split][br.leftChild];
              if (lS == Float.NEGATIVE_INFINITY) {
                continue;
              }
              float rS = iScore[split][end][br.rightChild];
              if (rS == Float.NEGATIVE_INFINITY) {
                continue;
              }
              float totL = pS + rS + oS;
              if (totL > oScore[start][split][br.leftChild]) {
                oScore[start][split][br.leftChild] = totL;
              }
              float totR = pS + lS + oS;
              if (totR > oScore[split][end][br.rightChild]) {
                oScore[split][end][br.rightChild] = totR;
              }
            }
          }
        }
        for (int s = 0; s < numStates; s++) {
          int max1 = narrowLExtent[end][s];
          if (max1 < start) {
            continue;
          }
          BinaryRule[] rules = bg.splitRulesWithRC(s);
          for (BinaryRule br : rules) {
            float oS = oScore[start][end][br.parent];
            if (oS == Float.NEGATIVE_INFINITY) {
              continue;
            }
            int min1 = narrowRExtent[start][br.leftChild];
            if (max1 < min1) {
              continue;
            }
            int min = min1;
            int max = max1;
            if (max - min > 2) {
              int min2 = wideLExtent[end][br.rightChild];
              min = (min1 > min2 ? min1 : min2);
              if (max1 < min) {
                continue;
              }
              int max2 = wideRExtent[start][br.leftChild];
              max = (max1 < max2 ? max1 : max2);
              if (max < min) {
                continue;
              }
            }
            float pS = br.score;
            for (int split = min; split <= max; split++) {
              float lS = iScore[start][split][br.leftChild];
              if (lS == Float.NEGATIVE_INFINITY) {
                continue;
              }
              float rS = iScore[split][end][br.rightChild];
              if (rS == Float.NEGATIVE_INFINITY) {
                continue;
              }
              float totL = pS + rS + oS;
              if (totL > oScore[start][split][br.leftChild]) {
                oScore[start][split][br.leftChild] = totL;
              }
              float totR = pS + lS + oS;
              if (totR > oScore[split][end][br.rightChild]) {
                oScore[split][end][br.rightChild] = totR;
              }
            }
          }
        }
        /*
          for (int s = 0; s < numStates; s++) {
          float oS = oScore[start][end][s];
          //if (iScore[start][end][s] == Float.NEGATIVE_INFINITY ||
          //             oS == Float.NEGATIVE_INFINITY)
          if (oS == Float.NEGATIVE_INFINITY)
          continue;
          BinaryRule[] rules = bg.splitRulesWithParent(s);
          for (int r=0; r<rules.length; r++) {
            BinaryRule br = rules[r];
            int min1 = narrowRExtent[start][br.leftChild];
            if (end < min1)
              continue;
            int max1 = narrowLExtent[end][br.rightChild];
            if (max1 < min1)
              continue;
            int min2 = wideLExtent[end][br.rightChild];
            int min = (min1 > min2 ? min1 : min2);
            if (max1 < min)
              continue;
            int max2 = wideRExtent[start][br.leftChild];
            int max = (max1 < max2 ? max1 : max2);
            if (max < min)
              continue;
float pS = (float) br.score;
for (int split = min; split <= max; split++) {
float lS = iScore[start][split][br.leftChild];
if (lS == Float.NEGATIVE_INFINITY)
          continue;
float rS = iScore[split][end][br.rightChild];
              if (rS == Float.NEGATIVE_INFINITY)
continue;
float totL = pS+rS+oS;
if (totL > oScore[start][split][br.leftChild]) {
oScore[start][split][br.leftChild] = totL;
}
float totR = pS+lS+oS;
if (totR > oScore[split][end][br.rightChild]) {
oScore[split][end][br.rightChild] = totR;
}
}
}
}
        */
      }
    }
  }

  /** Fills in the iScore array of each category over each span
   *  of length 2 or more.
   */
  void doInsideScores() {

    for (int diff = 2; diff <= length; diff++) {
      // usually stop one short because boundary symbol only combines
      // with whole sentence span
      for (int start = 0; start < ((diff == length) ? 1: length - diff); start++) {

        if (spillGuts) {
          tick("Binaries for span " + diff + " start " + start + " ...");
        }
        int end = start + diff;

        if (Test.constraints != null) {
          boolean skip = false;
          for (Test.Constraint c : Test.constraints) {
            if ((start > c.start && start < c.end && end > c.end) || (end > c.start && end < c.end && start < c.start)) {
              skip = true;
              break;
            }
          }
          if (skip) {
            continue;
          }
        }

        for (int leftState = 0; leftState < numStates; leftState++) {

          int narrowR = narrowRExtent[start][leftState];
          boolean iPossibleL = (narrowR < end); // can this left constituent leave space for a right constituent?
          if (!iPossibleL) {
            continue;
          }
          BinaryRule[] leftRules = bg.splitRulesWithLC(leftState);
          //      if (spillGuts) System.out.println("Found " + leftRules.length + " left rules for state " + stateNumberer.object(leftState));
          for (BinaryRule r : leftRules) {
            //      if (spillGuts) System.out.println("Considering rule for " + start + " to " + end + ": " + leftRules[i]);

            int narrowL = narrowLExtent[end][r.rightChild];
            boolean iPossibleR = (narrowL >= narrowR); // can this right constituent fit next to the left constituent?
            if (!iPossibleR) {
              continue;
            }
            int min1 = narrowR;
            int min2 = wideLExtent[end][r.rightChild];
            int min = (min1 > min2 ? min1 : min2);
            if (min > narrowL) { // can this right constituent stretch far enough to reach the left constituent?
              continue;
            }
            int max1 = wideRExtent[start][leftState];
            int max2 = narrowL;
            int max = (max1 < max2 ? max1 : max2);
            if (min > max) { // can this left constituent stretch far enough to reach the right constituent?
              continue;
            }
            float pS = r.score;
            int parentState = r.parent;
            float oldIScore = iScore[start][end][parentState];
            float bestIScore = oldIScore;
            boolean foundBetter;  // always set below for this rule
            //System.out.println("Min "+min+" max "+max+" start "+start+" end "+end);

            if (!Test.lengthNormalization) {
              // find the split that can use this rule to make the max score
              for (int split = min; split <= max; split++) {

                if (Test.constraints != null) {
                  boolean skip = false;
                  for (Test.Constraint c : Test.constraints) {
                    if (((start < c.start && end >= c.end) || (start <= c.start && end > c.end)) && split > c.start && split < c.end) {
                      skip = true;
                      break;
                    }
                    if ((start == c.start && split == c.end)) {
                      String tag = (String) stateNumberer.object(leftState);
                      Matcher m = c.state.matcher(tag);
                      if (!m.matches()) {
                        skip = true;
                        break;
                      }
                    }
                    if ((split == c.start && end == c.end)) {
                      String tag = (String) stateNumberer.object(r.rightChild);
                      Matcher m = c.state.matcher(tag);
                      if (!m.matches()) {
                        skip = true;
                        break;
                      }
                    }
                  }
                  if (skip) {
                    continue;
                  }
                }

                float lS = iScore[start][split][leftState];
                if (lS == Float.NEGATIVE_INFINITY) {
                  continue;
                }
                float rS = iScore[split][end][r.rightChild];
                if (rS == Float.NEGATIVE_INFINITY) {
                  continue;
                }
                float tot = pS + lS + rS;
                if (tot > bestIScore) {
                  bestIScore = tot;
                }
              } // for split point
              foundBetter = bestIScore > oldIScore;
            } else {
              // find split that uses this rule to make the max *length normalized* score
              int bestWordsInSpan = wordsInSpan[start][end][parentState];
              float oldNormIScore = oldIScore / bestWordsInSpan;
              float bestNormIScore = oldNormIScore;

              for (int split = min; split <= max; split++) {
                float lS = iScore[start][split][leftState];
                if (lS == Float.NEGATIVE_INFINITY) {

                  continue;
                }
                float rS = iScore[split][end][r.rightChild];
                if (rS == Float.NEGATIVE_INFINITY) {
                  continue;
                }
                float tot = pS + lS + rS;
                int newWordsInSpan = wordsInSpan[start][split][leftState] + wordsInSpan[split][end][r.rightChild];
                float normTot = tot / newWordsInSpan;
                if (normTot > bestNormIScore) {
                  bestIScore = tot;
                  bestNormIScore = normTot;
                  bestWordsInSpan = newWordsInSpan;
                }
              } // for split point
              foundBetter = bestNormIScore > oldNormIScore;
              if (foundBetter) {
                wordsInSpan[start][end][parentState] = bestWordsInSpan;
              }
            } // fi Test.lengthNormalization
            if (foundBetter) { // this way of making "parentState" is better than previous
              iScore[start][end][parentState] = bestIScore;

              //              if (spillGuts) System.out.println("Could build " + stateNumberer.object(parentState) + " from " + start + " to " + end);
              if (oldIScore == Float.NEGATIVE_INFINITY) {
                if (start > narrowLExtent[end][parentState]) {
                  narrowLExtent[end][parentState] = start;
                  wideLExtent[end][parentState] = start;
                } else {
                  if (start < wideLExtent[end][parentState]) {
                    wideLExtent[end][parentState] = start;
                  }
                }
                if (end < narrowRExtent[start][parentState]) {
                  narrowRExtent[start][parentState] = end;
                  wideRExtent[start][parentState] = end;
                } else {
                  if (end > wideRExtent[start][parentState]) {
                    wideRExtent[start][parentState] = end;
                  }
                }
              }
            } // end if foundBetter
          } // end for leftRules
        } // end for leftState
        // do right restricted rules
        for (int rightState = 0; rightState < numStates; rightState++) {
          int narrowL = narrowLExtent[end][rightState];
          boolean iPossibleR = (narrowL > start);
          if (!iPossibleR) {
            continue;
          }
          BinaryRule[] rightRules = bg.splitRulesWithRC(rightState);
          //      if (spillGuts) System.out.println("Found " + rightRules.length + " right rules for state " + stateNumberer.object(rightState));
          for (BinaryRule r : rightRules) {
            //      if (spillGuts) System.out.println("Considering rule for " + start + " to " + end + ": " + rightRules[i]);

            int narrowR = narrowRExtent[start][r.leftChild];
            boolean iPossibleL = (narrowR <= narrowL);
            if (!iPossibleL) {
              continue;
            }
            int min1 = narrowR;
            int min2 = wideLExtent[end][rightState];
            int min = (min1 > min2 ? min1 : min2);
            if (min > narrowL) {
              continue;
            }
            int max1 = wideRExtent[start][r.leftChild];
            int max2 = narrowL;
            int max = (max1 < max2 ? max1 : max2);
            if (min > max) {
              continue;
            }
            float pS = r.score;
            int parentState = r.parent;
            float oldIScore = iScore[start][end][parentState];
            float bestIScore = oldIScore;
            boolean foundBetter; // always initialized below
            //System.out.println("Start "+start+" end "+end+" min "+min+" max "+max);
            if (!Test.lengthNormalization) {
              // find the split that can use this rule to make the max score
              for (int split = min; split <= max; split++) {

                if (Test.constraints != null) {
                  boolean skip = false;
                  for (Test.Constraint c : Test.constraints) {
                    if (((start < c.start && end >= c.end) || (start <= c.start && end > c.end)) && split > c.start && split < c.end) {
                      skip = true;
                      break;
                    }
                    if ((start == c.start && split == c.end)) {
                      String tag = (String) stateNumberer.object(r.leftChild);
                      Matcher m = c.state.matcher(tag);
                      if (!m.matches()) {
                        //if (!tag.startsWith(c.state+"^")) {
                        skip = true;
                        break;
                      }
                    }
                    if ((split == c.start && end == c.end)) {
                      String tag = (String) stateNumberer.object(rightState);
                      Matcher m = c.state.matcher(tag);
                      if (!m.matches()) {
                        //if (!tag.startsWith(c.state+"^")) {
                        skip = true;
                        break;
                      }
                    }
                  }
                  if (skip) {
                    continue;
                  }
                }

                float lS = iScore[start][split][r.leftChild];
                if (lS == Float.NEGATIVE_INFINITY) {
                  continue;
                }
                float rS = iScore[split][end][rightState];
                if (rS == Float.NEGATIVE_INFINITY) {
                  continue;
                }
                float tot = pS + lS + rS;
                if (tot > bestIScore) {
                  bestIScore = tot;
                }
              } // end for split
              foundBetter = bestIScore > oldIScore;
            } else {
              // find split that uses this rule to make the max *length normalized* score
              int bestWordsInSpan = wordsInSpan[start][end][parentState];
              float oldNormIScore = oldIScore / bestWordsInSpan;
              float bestNormIScore = oldNormIScore;
              for (int split = min; split <= max; split++) {
                float lS = iScore[start][split][r.leftChild];
                if (lS == Float.NEGATIVE_INFINITY) {
                  continue;
                }
                float rS = iScore[split][end][rightState];
                if (rS == Float.NEGATIVE_INFINITY) {
                  continue;
                }
                float tot = pS + lS + rS;
                int newWordsInSpan = wordsInSpan[start][split][r.leftChild] + wordsInSpan[split][end][rightState];
                float normTot = tot / newWordsInSpan;
                if (normTot > bestNormIScore) {
                  bestIScore = tot;
                  bestNormIScore = normTot;
                  bestWordsInSpan = newWordsInSpan;
                }
              } // end for split
              foundBetter = bestNormIScore > oldNormIScore;
              if (foundBetter) {
                wordsInSpan[start][end][parentState] = bestWordsInSpan;
              }
            } // end if lengthNormalization
            if (foundBetter) { // this way of making "parentState" is better than previous
              iScore[start][end][parentState] = bestIScore;
              //              if (spillGuts) System.out.println("Could build " + stateNumberer.object(parentState) + " from " + start + " to " + end);
              if (oldIScore == Float.NEGATIVE_INFINITY) {
                if (start > narrowLExtent[end][parentState]) {
                  narrowLExtent[end][parentState] = start;
                  wideLExtent[end][parentState] = start;
                } else {
                  if (start < wideLExtent[end][parentState]) {
                    wideLExtent[end][parentState] = start;
                  }
                }
                if (end < narrowRExtent[start][parentState]) {
                  narrowRExtent[start][parentState] = end;
                  wideRExtent[start][parentState] = end;
                } else {
                  if (end > wideRExtent[start][parentState]) {
                    wideRExtent[start][parentState] = end;
                  }
                }
              }
            } // end if foundBetter
          } // for rightRules
        } // for rightState
        if (spillGuts) {
          tick("Unaries for span " + diff + "...");
        }
        // do unary rules -- one could promote this loop and put start inside
        for (int state = 0; state < numStates; state++) {
          float iS = iScore[start][end][state];
          if (iS == Float.NEGATIVE_INFINITY) {
            continue;
          }

          UnaryRule[] unaries = ug.closedRulesByChild(state);
          for (UnaryRule ur : unaries) {

            if (Test.constraints != null) {
              boolean skip = false;
              for (Test.Constraint c : Test.constraints) {
                if ((start == c.start && end == c.end)) {
                  String tag = (String) stateNumberer.object(ur.parent);
                  Matcher m = c.state.matcher(tag);
                  if (!m.matches()) {
                    //if (!tag.startsWith(c.state+"^")) {
                    skip = true;
                    break;
                  }
                }
              }
              if (skip) {
                continue;
              }
            }

            int parentState = ur.parent;
            float pS = ur.score;
            float tot = iS + pS;
            float cur = iScore[start][end][parentState];
            boolean foundBetter;  // always set below
            if (Test.lengthNormalization) {
              int totWordsInSpan = wordsInSpan[start][end][state];
              float normTot = tot / totWordsInSpan;
              int curWordsInSpan = wordsInSpan[start][end][parentState];
              float normCur = cur / curWordsInSpan;
              foundBetter = normTot > normCur;
              if (foundBetter) {
                wordsInSpan[start][end][parentState] = wordsInSpan[start][end][state];
              }
            } else {
              foundBetter = (tot > cur);
            }
            if (foundBetter) {
              //              if (spillGuts) System.out.println("Could build " + stateNumberer.object(parentState) + " from " + start + " to " + end);
              iScore[start][end][parentState] = tot;
              if (cur == Float.NEGATIVE_INFINITY) {
                if (start > narrowLExtent[end][parentState]) {
                  narrowLExtent[end][parentState] = start;
                  wideLExtent[end][parentState] = start;
                } else {
                  if (start < wideLExtent[end][parentState]) {
                    wideLExtent[end][parentState] = start;
                  }
                }
                if (end < narrowRExtent[start][parentState]) {
                  narrowRExtent[start][parentState] = end;
                  wideRExtent[start][parentState] = end;
                } else {
                  if (end > wideRExtent[start][parentState]) {
                    wideRExtent[start][parentState] = end;
                  }
                }
              }
            } // end if foundBetter
          } // for UnaryRule r
        } // for unary rules
      } // for start
    } // for diff (i.e., span)
  } // end doInsideScores()


  private void initializeChart(Lattice lr) {
    for (LatticeEdge edge : lr) {
      int start = edge.start;
      int end = edge.end;
      String word = edge.word;
      
      // Add pre-terminals, augmented with edge weights
      for (int state = 0; state < numStates; state++) {
        if (isTag[state]) {
          IntTaggedWord itw = new IntTaggedWord(wordNumberer.number(word), Numberer.translate(stateSpace, "tags", state));

          float newScore = lex.score(itw, start) + (float) edge.weight;
          if (newScore > iScore[start][end][state]) {
            iScore[start][end][state] = newScore;
            narrowRExtent[start][state] = Math.min(end, narrowRExtent[start][state]);
            narrowLExtent[end][state] = Math.max(start, narrowLExtent[end][state]);
            wideRExtent[start][state] = Math.max(end, wideRExtent[start][state]);
            wideLExtent[end][state] = Math.min(start, wideLExtent[end][state]);
          }
        }
      }
      
      // Give scores to all tags if the parse fails (more flexible tagging)
      if (floodTags && (!Test.noRecoveryTagging)) {
        for (int state = 0; state < numStates; state++) {
          float iS = iScore[start][end][state];
          if (isTag[state] && iS == Float.NEGATIVE_INFINITY) {
            iScore[start][end][state] = -1000.0f + (float) edge.weight;
            narrowRExtent[start][state] = end;
            narrowLExtent[end][state] = start;
            wideRExtent[start][state] = end;
            wideLExtent[end][state] = start;
          }
        }
      }
      
      // Add unary rules (possibly chains) that terminate in POS tags
      for (int state = 0; state < numStates; state++) {
        float iS = iScore[start][end][state];
        if (iS == Float.NEGATIVE_INFINITY) {
          continue;
        }
        UnaryRule[] unaries = ug.closedRulesByChild(state);
        for (UnaryRule ur : unaries) {
          int parentState = ur.parent;
          float pS = ur.score;
          float tot = iS + pS;
          if (tot > iScore[start][end][parentState]) {
            iScore[start][end][parentState] = tot;
            narrowRExtent[start][parentState] = Math.min(end, narrowRExtent[start][parentState]);
            narrowLExtent[end][parentState] = Math.max(start, narrowLExtent[end][parentState]);
            wideRExtent[start][parentState] = Math.max(end, wideRExtent[start][parentState]);
            wideLExtent[end][parentState] = Math.min(start, wideLExtent[end][parentState]);
//            narrowRExtent[start][parentState] = start + 1; //end
//            narrowLExtent[end][parentState] = end - 1; //start
//            wideRExtent[start][parentState] = start + 1; //end
//            wideLExtent[end][parentState] = end - 1; //start
          }
        }
      }
    }
  }


  private void initializeChart(List sentence) {
    int boundary = wordNumberer.number(Lexicon.BOUNDARY);

    for (int start = 0; start + 1 <= length; start++) {
      if (Test.maxSpanForTags > 1) { // only relevant for parsing single words as multiple input tokens.
        // note we don't look for "words" including the end symbol!
        for (int end = start + 1; (end < length - 1 && end - start <= Test.maxSpanForTags) || (start + 1 == end); end++) {
          StringBuilder word = new StringBuilder();
          // this is ugly and should be fixed...
          for (int i = start; i < end; i++) {
            if (sentence.get(i) instanceof StringLabel) {
              word.append(((StringLabel) sentence.get(i)).value());
            } else {
              word.append((String) sentence.get(i));
            }
          }
          for (int state = 0; state < numStates; state++) {
            float iS = iScore[start][end][state];
            if (iS == Float.NEGATIVE_INFINITY && isTag[state]) {
              IntTaggedWord itw = new IntTaggedWord(wordNumberer.number(word.toString()), Numberer.translate(stateSpace, "tags", state));
              iScore[start][end][state] = lex.score(itw, start);
              if (iScore[start][end][state] > Float.NEGATIVE_INFINITY) {
                narrowRExtent[start][state] = start + 1;
                narrowLExtent[end][state] = end - 1;
                wideRExtent[start][state] = start + 1;
                wideLExtent[end][state] = end - 1;
              }
            }
          }
        }

      } else { // "normal" chart initialization of the [start,start+1] cell

        int word = words[start];
        int end = start + 1;
        Arrays.fill(tags[start], false);
        String trueTagStr = null;
        if (sentence.get(start) instanceof HasTag) {
          trueTagStr = ((HasTag) sentence.get(start)).tag();
          if ("".equals(trueTagStr)) {
            trueTagStr = null;
          }
        }
        boolean assignedSomeTag = false;

        if ( ! floodTags || word == boundary) {
          // in this case we generate the taggings in the lexicon,
          // which may itself be tagging flexibly or using a strict lexicon.
          if (dumpTagging) {
            EncodingPrintWriter.err.println("Normal tagging " + Numberer.getGlobalNumberer("words").object(word) + " [" + word + "]", "UTF-8");
          }
          for (Iterator<IntTaggedWord> taggingI = lex.ruleIteratorByWord(word, start); taggingI.hasNext(); ) {
            IntTaggedWord tagging = taggingI.next();
            int state = Numberer.translate("tags", stateSpace, tagging.tag);
            if (trueTagStr != null) { // if word was supplied with a POS tag, skip all taggings not basicCategory() compatible with supplied tag.
              if ((!Test.forceTagBeginnings && !tlp.basicCategory(tagging.tagString()).equals(trueTagStr)) || 
                  (Test.forceTagBeginnings &&  !tagging.tagString().startsWith(trueTagStr))) {
                if (dumpTagging) {
                  EncodingPrintWriter.err.println("  Skipping " + tagging + " as it doesn't match trueTagStr: " + trueTagStr, "UTF-8");
                }
                continue;
              }
            }
            // try {
            float lexScore = lex.score(tagging, start); // score the cell according to P(word|tag) in the lexicon
            if (lexScore > Float.NEGATIVE_INFINITY) {
              assignedSomeTag = true;
              iScore[start][end][state] = lexScore;
              narrowRExtent[start][state] = end;
              narrowLExtent[end][state] = start;
              wideRExtent[start][state] = end;
              wideLExtent[end][state] = start;
            }
            // } catch (Exception e) {
            // e.printStackTrace();
            // System.out.println("State: " + state + " tags " + Numberer.getGlobalNumberer("tags").object(tagging.tag));
            // }
            int tag = tagging.tag;
            tags[start][tag] = true;
            if (dumpTagging) {
              EncodingPrintWriter.err.println("Word pos " + start + " tagging " + tagging + " score " + iScore[start][start + 1][state] + " [state " + Numberer.object("states", state) + " = " + state + "]", "UTF-8");
            }
            //if (start == length-2 && tagging.parent == puncTag)
            //  lastIsPunc = true;
          }
        } // end if ( ! floodTags || word == boundary)

        if ( ! assignedSomeTag) {
          // If you got here, either you were using forceTags (gold tags)
          // and the gold tag was not seen with that word in the training data
          // or we are in floodTags=true (recovery parse) mode
          // Here, we give words all tags for
          // which the lexicon score is not -Inf, not just seen or
          // specified taggings
          if (dumpTagging) {
            EncodingPrintWriter.err.println("Forced FlexiTagging " + Numberer.getGlobalNumberer("words").object(word), "UTF-8");
          }
          for (int state = 0; state < numStates; state++) {
            if (isTag[state] && iScore[start][end][state] == Float.NEGATIVE_INFINITY) {
              if (trueTagStr != null) {
                String tagString = (String) stateNumberer.object(state);
                if ( ! tlp.basicCategory(tagString).equals(trueTagStr)) {
                  continue;
                }
              }

              float lexScore = lex.score(new IntTaggedWord(word, Numberer.translate(stateSpace, "tags", state)), start);
              if (lexScore > Float.NEGATIVE_INFINITY) {
                iScore[start][end][state] = lexScore;
                narrowRExtent[start][state] = end;
                narrowLExtent[end][state] = start;
                wideRExtent[start][state] = end;
                wideLExtent[end][state] = start;
              }
              if (dumpTagging) {
                EncodingPrintWriter.err.println("Word pos " + start + " tagging " + (new IntTaggedWord(word, Numberer.translate(stateSpace, "tags", state))) + " score " + iScore[start][start + 1][state]  + " [state " + Numberer.object("states", state) + " = " + state + "]", "UTF-8");
              }
            }
          }
        } // end if ! assignedSomeTag

        // tag multi-counting
        if (op.dcTags) {
          for (int state = 0; state < numStates; state++) {
            if (isTag[state]) {
              iScore[start][end][state] *= (1.0 + Test.depWeight);
            }
          }
        }

        if (floodTags && (!Test.noRecoveryTagging) && ! (word == boundary)) {
          // if parse failed because of tag coverage, we put in all tags with
          // a score of -1000, by fiat.  You get here from the invocation of
          // parse(ls) inside parse(ls) *after* floodTags has been turned on.
          // Search above for "floodTags = true".
          if (dumpTagging) {
            EncodingPrintWriter.err.println("Flooding tags for " + Numberer.getGlobalNumberer("words").object(word), "UTF-8");
          }
          for (int state = 0; state < numStates; state++) {
            if (isTag[state] && iScore[start][end][state] == Float.NEGATIVE_INFINITY) {
              iScore[start][end][state] = -1000.0f;
              narrowRExtent[start][state] = end;
              narrowLExtent[end][state] = start;
              wideRExtent[start][state] = end;
              wideLExtent[end][state] = start;
            }
          }
        }

        // Apply unary rules in diagonal cells of chart
        if (spillGuts) {
          tick("Terminal Unary...");
        }
        for (int state = 0; state < numStates; state++) {
          float iS = iScore[start][end][state];
          if (iS == Float.NEGATIVE_INFINITY) {
            continue;
          }
          UnaryRule[] unaries = ug.closedRulesByChild(state);
          for (UnaryRule ur : unaries) {
            int parentState = ur.parent;
            float pS = ur.score;
            float tot = iS + pS;
            if (tot > iScore[start][end][parentState]) {
              iScore[start][end][parentState] = tot;
              narrowRExtent[start][parentState] = end;
              narrowLExtent[end][parentState] = start;
              wideRExtent[start][parentState] = end;
              wideLExtent[end][parentState] = start;
            }
          }
        }
        if (spillGuts) {
          tick("Next word...");
        }
      }
    }
  }

  public boolean hasParse() {
    return getBestScore() > Double.NEGATIVE_INFINITY;
  }


  private static final double TOL = 1e-5;

  protected static boolean matches(double x, double y) {
    return (Math.abs(x - y) / (Math.abs(x) + Math.abs(y) + 1e-10) < TOL);
  }


  public double getBestScore() {
    return getBestScore(goalStr);
  }

  public double getBestScore(String stateName) {
    if (length > arraySize) {
      return Double.NEGATIVE_INFINITY;
    }
    if ( ! stateNumberer.hasSeen(stateName)) {
      return Double.NEGATIVE_INFINITY;
    }
    int goal = stateNumberer.number(stateName);
    return iScore[0][length][goal];
  }


  public Tree getBestParse() {
    int start = 0;
    int end = length;

    int goal = stateNumberer.number(goalStr);
    Tree internalTree = extractBestParse(goal, start, end);
    //System.out.println("Got internal best parse...");
    if (internalTree == null) {
      System.err.println("Warning: no parse found in ExhaustivePCFGParser.extractBestParse");
    } // else {
      // restoreUnaries(internalTree);
    // }
    // System.out.println("Restored unaries...");
    return internalTree;
    //TreeTransformer debinarizer = BinarizerFactory.getDebinarizer();
    //return debinarizer.transformTree(internalTree);
  }


  protected Tree extractBestParse(int goal, int start, int end) {
    // find source of inside score
    // no backtraces so we can speed up the parsing for its primary use
    double bestScore = iScore[start][end][goal];
    double normBestScore = Test.lengthNormalization ? (bestScore / wordsInSpan[start][end][goal]) : bestScore;
    String goalStr = (String) stateNumberer.object(goal);
    // System.err.println("Searching for "+goalStr+" from "+start+" to "+end+" scored "+bestScore +
    //                " tagNumberer.hasSeen: " + tagNumberer.hasSeen(goalStr));
    // check tags
    if (end - start <= Test.maxSpanForTags && tagNumberer.hasSeen(goalStr)) {
      if (Test.maxSpanForTags > 1) {
        Tree wordNode = null;
        if (sentence != null) {
          StringBuilder word = new StringBuilder();
          for (int i = start; i < end; i++) {
            if (sentence.get(i) instanceof StringLabel) {
              word.append(((StringLabel) sentence.get(i)).value());
            } else {
              word.append((String) sentence.get(i));
            }
          }
          wordNode = tf.newLeaf(new StringLabel(word.toString()));
     
        } else if (lr != null) {
          List<LatticeEdge> latticeEdges = lr.getEdgesOverSpan(start, end);
          for (LatticeEdge edge : latticeEdges) {
            IntTaggedWord itw = new IntTaggedWord(wordNumberer.number(edge.word), Numberer.translate(stateSpace, "tags", goal));
            
            float tagScore = (floodTags) ? -1000.0f : lex.score(itw, start);
            if (matches(bestScore, tagScore + (float) edge.weight)) {
              wordNode = tf.newLeaf(new StringLabel(edge.word));
              break;
            }
          }
          if (wordNode == null) {
            throw new RuntimeException("could not find matching word from lattice in parse reconstruction");
          }
        
        } else {
          throw new RuntimeException("attempt to get word when sentence and lattice are null!");
        }
        Tree tagNode = tf.newTreeNode(new StringLabel(goalStr), Collections.singletonList(wordNode));
        tagNode.setScore(bestScore);
        return tagNode;
      } else {  // normal lexicon is single words case
        IntTaggedWord tagging = new IntTaggedWord(words[start], tagNumberer.number(goalStr));
        float tagScore = lex.score(tagging, start);
        if (tagScore > Float.NEGATIVE_INFINITY || floodTags) {
          // return a pre-terminal tree
          String wordStr = (String) wordNumberer.object(words[start]);
          StringLabel leafLabel;
          if (offsets[start] != null) {
            leafLabel = new StringLabel(
                wordStr, offsets[start].getSource(), offsets[start].getTarget());
          } else {
            leafLabel = new StringLabel(wordStr);
          }
          Tree wordNode = tf.newLeaf(leafLabel);
          Tree tagNode = tf.newTreeNode(new StringLabel(goalStr),
                                        Collections.singletonList(wordNode));
          tagNode.setScore(bestScore);
          // System.err.println("    Found tag node: "+tagNode);
          return tagNode;
        }
      }
    }
    // check binaries first
    for (int split = start + 1; split < end; split++) {
      for (Iterator<BinaryRule> binaryI = bg.ruleIteratorByParent(goal); binaryI.hasNext(); ) {
        BinaryRule br = binaryI.next();
        double score = br.score + iScore[start][split][br.leftChild] + iScore[split][end][br.rightChild];
        boolean matches;
        if (Test.lengthNormalization) {
          double normScore = score / (wordsInSpan[start][split][br.leftChild] + wordsInSpan[split][end][br.rightChild]);
          matches = matches(normScore, normBestScore);
        } else {
          matches = matches(score, bestScore);
        }
        if (matches) {
          // build binary split
          Tree leftChildTree = extractBestParse(br.leftChild, start, split);
          Tree rightChildTree = extractBestParse(br.rightChild, split, end);
          List<Tree> children = new ArrayList<Tree>();
          children.add(leftChildTree);
          children.add(rightChildTree);
          Tree result = tf.newTreeNode(new StringLabel(goalStr), children);
          result.setScore(score);
          // System.err.println("    Found Binary node: "+result);
          return result;
        }
      }
    }
    // check unaries
    // note that even though we parse with the unary-closed grammar, we can
    // extract the best parse with the non-unary-closed grammar, since all
    // the intermediate states in the chain must have been built, and hence
    // we can exploit the sparser space and reconstruct the full tree as we go.
    // for (Iterator<UnaryRule> unaryI = ug.closedRuleIteratorByParent(goal); unaryI.hasNext(); ) {
    for (Iterator<UnaryRule> unaryI = ug.ruleIteratorByParent(goal); unaryI.hasNext(); ) {
      UnaryRule ur = unaryI.next();
      // System.err.println("  Trying " + ur + " dtr score: " + iScore[start][end][ur.child]);
      double score = ur.score + iScore[start][end][ur.child];
      boolean matches;
      if (Test.lengthNormalization) {
        double normScore = score / wordsInSpan[start][end][ur.child];
        matches = matches(normScore, normBestScore);
      } else {
        matches = matches(score, bestScore);
      }
      if (ur.child != ur.parent && matches) {
        // build unary
        Tree childTree = extractBestParse(ur.child, start, end);
        Tree result = tf.newTreeNode(new StringLabel(goalStr),
                                     Collections.singletonList(childTree));
        // System.err.println("    Matched!  Unary node: "+result);
        result.setScore(score);
        return result;
      }
    }
    System.err.println("Warning: no parse found in ExhaustivePCFGParser.extractBestParse: failing on: [" + start + ", " + end + "] looking for " + goalStr);
    return null;
  }


  /* -----------------------
  // No longer needed: extracBestParse restores unaries as it goes
  protected void restoreUnaries(Tree t) {
    //System.out.println("In restoreUnaries...");
    for (Tree node : t) {
      System.err.println("Doing node: "+node.label());
      if (node.isLeaf() || node.isPreTerminal() || node.numChildren() != 1) {
        //System.out.println("Skipping node: "+node.label());
        continue;
      }
      //System.out.println("Not skipping node: "+node.label());
      Tree parent = node;
      Tree child = node.children()[0];
      List path = ug.getBestPath(stateNumberer.number(parent.label().value()), stateNumberer.number(child.label().value()));
      System.err.println("Got path: "+path);
      int pos = 1;
      while (pos < path.size() - 1) {
        int interState = ((Integer) path.get(pos)).intValue();
        Tree intermediate = tf.newTreeNode(new StringLabel((String) stateNumberer.object(interState)), parent.getChildrenAsList());
        parent.setChildren(Collections.singletonList(intermediate));
        pos++;
      }
      //System.out.println("Done with node: "+node.label());
    }
  }
  ---------------------- */


  /**
   * Return all best parses (except no ties allowed on POS tags?).
   * Even though we parse with the unary-closed grammar, since all the
   * intermediate states in a chain must have been built, we can
   * reconstruct the unary chain as we go using the non-unary-closed grammar.
   */
  protected List<Tree> extractBestParses(int goal, int start, int end) {
    // find sources of inside score
    // no backtraces so we can speed up the parsing for its primary use
    double bestScore = iScore[start][end][goal];
    String goalStr = (String) stateNumberer.object(goal);
    //System.out.println("Searching for "+goalStr+" from "+start+" to "+end+" scored "+bestScore);
    // check tags
    if (end - start == 1 && tagNumberer.hasSeen(goalStr)) {
      IntTaggedWord tagging = new IntTaggedWord(words[start], tagNumberer.number(goalStr));
      float tagScore = lex.score(tagging, start);
      if (tagScore > Float.NEGATIVE_INFINITY || floodTags) {
        // return a pre-terminal tree
        String wordStr = (String) wordNumberer.object(words[start]);
        Tree wordNode = tf.newLeaf(new StringLabel(wordStr));
        Tree tagNode = tf.newTreeNode(new StringLabel(goalStr),
                                      Collections.singletonList(wordNode));
        //System.out.println("Tag node: "+tagNode);
        return Collections.singletonList(tagNode);
      }
    }
    // check binaries first
    List<Tree> bestTrees = new ArrayList<Tree>();
    for (int split = start + 1; split < end; split++) {
      for (Iterator<BinaryRule> binaryI = bg.ruleIteratorByParent(goal); binaryI.hasNext(); ) {
        BinaryRule br = binaryI.next();
        double score = br.score + iScore[start][split][br.leftChild] + iScore[split][end][br.rightChild];
        if (matches(score, bestScore)) {
          // build binary split
          List<Tree> leftChildTrees = extractBestParses(br.leftChild, start, split);
          List<Tree> rightChildTrees = extractBestParses(br.rightChild, split, end);
          // System.out.println("Found a best way to build " + goalStr + "(" +
          //                 start + "," + end + ") with " +
          //                 leftChildTrees.size() + "x" +
          //                 rightChildTrees.size() + " ways to build.");
          for (Tree leftChildTree : leftChildTrees) {
            for (Tree rightChildTree : rightChildTrees) {
              List<Tree> children = new ArrayList<Tree>();
              children.add(leftChildTree);
              children.add(rightChildTree);
              Tree result = tf.newTreeNode(new StringLabel(goalStr), children);
              //System.out.println("Binary node: "+result);
              bestTrees.add(result);
            }
          }
        }
      }
    }
    // check unaries
    for (Iterator<UnaryRule> unaryI = ug.ruleIteratorByParent(goal); unaryI.hasNext(); ) {
      UnaryRule ur = unaryI.next();
      double score = ur.score + iScore[start][end][ur.child];
      if (ur.child != ur.parent && matches(score, bestScore)) {
        // build unary
        List<Tree> childTrees = extractBestParses(ur.child, start, end);
        for (Tree childTree : childTrees) {
          Tree result = tf.newTreeNode(new StringLabel(goalStr),
                                       Collections.singletonList(childTree));
          //System.out.println("Unary node: "+result);
          bestTrees.add(result);
        }
      }
    }
    if (bestTrees.isEmpty()) {
      System.err.println("Warning: no parse found in ExhaustivePCFGParser.extractBestParse: failing on: [" + start + ", " + end + "] looking for " + goalStr);
    }
    return bestTrees;
  }


  /** Get k good parses for the sentence.  It is expected that the
   *  parses returned approximate the k best parses, but without any
   *  guarantee that the exact list of k best parses has been produced.
   *
   *  @param k The number of good parses to return
   *  @return A list of k good parses for the sentence, with
   *         each accompanied by its score
   */
  public List<ScoredObject<Tree>> getKGoodParses(int k) {
    return getKBestParses(k);
  }

  /** Get k parse samples for the sentence.  It is expected that the
   *  parses are sampled based on their relative probability.
   *
   *  @param k The number of sampled parses to return
   *  @return A list of k parse samples for the sentence, with
   *         each accompanied by its score
   */
  public List<ScoredObject<Tree>> getKSampledParses(int k) {
    throw new UnsupportedOperationException("ExhaustivePCFGParser doesn't sample.");
  }


  //
  // BEGIN K-BEST STUFF
  // taken straight out of "Better k-best Parsing" by Liang Huang and David
  // Chiang
  //

  /** Get the exact k best parses for the sentence.
   *
   *  @param k The number of best parses to return
   *  @return The exact k best parses for the sentence, with
   *         each accompanied by its score (typically a
   *         negative log probability).
   */
  public List<ScoredObject<Tree>> getKBestParses(int k) {

    cand = new HashMap<Vertex,PriorityQueue<Derivation>>();
    dHat = new HashMap<Vertex,LinkedList<Derivation>>();

    int start = 0;
    int end = length;
    int goal = stateNumberer.number(goalStr);

    Vertex v = new Vertex(goal, start, end);
    List<ScoredObject<Tree>> kBestTrees = new ArrayList<ScoredObject<Tree>>();
    for (int i = 1; i <= k; i++) {
      Tree internalTree = getTree(v, i, k);
      if (internalTree == null) { break; }
      // restoreUnaries(internalTree);
      kBestTrees.add(new ScoredObject<Tree>(internalTree, dHat.get(v).get(i-1).score));
    }
    return kBestTrees;
  }

  /** Get the kth best, when calculating kPrime best (e.g. 2nd best of 5). */
  private Tree getTree(Vertex v, int k, int kPrime) {
    lazyKthBest(v, k, kPrime);
    String goalStr = (String)stateNumberer.object(v.goal);
    int start = v.start;
    // int end = v.end;

    List<Derivation> dHatV = dHat.get(v);

    if (isTag[v.goal]) {
      IntTaggedWord tagging = new IntTaggedWord(words[start], tagNumberer.number(goalStr));
      float tagScore = lex.score(tagging, start);
      if (tagScore > Float.NEGATIVE_INFINITY || floodTags) {
        // return a pre-terminal tree
        String wordStr = (String) wordNumberer.object(words[start]);
        Tree wordNode = tf.newLeaf(new StringLabel(wordStr));
        return tf.newTreeNode(new StringLabel(goalStr),
                                      Collections.singletonList(wordNode));
      } else {
        assert false;
      }
    }

    if (k-1 >= dHatV.size()) {
      return null;
    }

    Derivation d = dHatV.get(k-1);

    List<Tree> children = new ArrayList<Tree>();
    for (int i = 0; i < d.arc.size(); i++) {
      Vertex child = d.arc.tails.get(i);
      Tree t = getTree(child, d.j.get(i), kPrime);
      assert (t != null);
      children.add(t);
    }

    return tf.newTreeNode(new StringLabel(goalStr),children);
  }

  private static class Vertex {
    public final int goal;
    public final int start;
    public final int end;

    public Vertex(int goal, int start, int end) {
      this.goal = goal;
      this.start = start;
      this.end = end;
    }

    public boolean equals(Object o) {
      if (!(o instanceof Vertex)) { return false; }
      Vertex v = (Vertex)o;
      return (v.goal == goal && v.start == start && v.end == end);
    }

    private int hc = -1;

    public int hashCode() {
      if (hc == -1) {
        hc = goal + (17 * (start + (17 * end)));
      }
      return hc;
    }

    public String toString() {
      return goal+"["+start+","+end+"]";
    }
  }

  private static class Arc {
    public final List<Vertex> tails;
    public final Vertex head;
    public final double ruleScore; // for convenience

    public Arc(List<Vertex> tails, Vertex head, double ruleScore) {
      this.tails = Collections.unmodifiableList(tails);
      this.head = head;
      this.ruleScore = ruleScore;
      // TODO: add check that rule is compatible with head and tails!
    }

    public boolean equals(Object o) {
      if (!(o instanceof Arc)) { return false; }
      Arc a = (Arc) o;
      return a.head.equals(head) && a.tails.equals(tails);
    }

    private int hc = -1;

    public int hashCode() {
      if (hc == -1) {
        hc = head.hashCode() + (17 * tails.hashCode());
      }
      return hc;
    }

    public int size() { return tails.size(); }
  }

  private static class Derivation {
    public final Arc arc;
    public final List<Integer> j;
    public final double score;  // score does not affect equality (?)
    public final List<Double> childrenScores;

    public Derivation(Arc arc, List<Integer> j, double score, List<Double> childrenScores) {
      this.arc = arc;
      this.j = Collections.unmodifiableList(j);
      this.score = score;
      this.childrenScores = Collections.unmodifiableList(childrenScores);
    }

    public boolean equals(Object o) {
      if (!(o instanceof Derivation)) { return false; }
      Derivation d = (Derivation)o;
      if (arc == null && d.arc != null || arc != null && d.arc == null) { return false; }
      return ((arc == null && d.arc == null || d.arc.equals(arc)) && d.j.equals(j));
    }

    private int hc = -1;

    public int hashCode() {
      if (hc == -1) {
        hc = (arc == null ? 0 : arc.hashCode()) + (17 * j.hashCode());
      }
      return hc;
    }
  }

  private List<Arc> getBackwardsStar(Vertex v) {

    List<Arc> bs = new ArrayList<Arc>();

    // pre-terminal??
    if (isTag[v.goal]) {
      List<Vertex> tails = new ArrayList<Vertex>();
      double score = iScore[v.start][v.end][v.goal];
      Arc arc = new Arc(tails, v, score);
      bs.add(arc);
    }

    // check binaries
    for (int split = v.start + 1; split < v.end; split++) {
      for (BinaryRule br : bg.ruleListByParent(v.goal)) {
        Vertex lChild = new Vertex(br.leftChild, v.start, split);
        Vertex rChild = new Vertex(br.rightChild, split, v.end);
        List<Vertex> tails = new ArrayList<Vertex>();
        tails.add(lChild);
        tails.add(rChild);
        Arc arc = new Arc(tails, v, br.score);
        bs.add(arc);
      }
    }

    // check unaries
    for (UnaryRule ur : ug.rulesByParent(v.goal)) {
      Vertex child = new Vertex(ur.child, v.start, v.end);
      List<Vertex> tails = new ArrayList<Vertex>();
      tails.add(child);
      Arc arc = new Arc(tails, v, ur.score);
      bs.add(arc);
    }

    return bs;
  }

  private Map<Vertex,PriorityQueue<Derivation>> cand = new HashMap<Vertex,PriorityQueue<Derivation>>();
  private Map<Vertex,LinkedList<Derivation>> dHat = new HashMap<Vertex,LinkedList<Derivation>>();

  private PriorityQueue<Derivation> getCandidates(Vertex v, int k) {
    PriorityQueue<Derivation> candV = cand.get(v);
    if (candV == null) {
      candV = new BinaryHeapPriorityQueue<Derivation>();
      List<Arc> bsV = getBackwardsStar(v);

      for (Arc arc : bsV) {
        int size = arc.size();
        double score = arc.ruleScore;
        List<Double> childrenScores = new ArrayList<Double>();
        for (int i = 0; i < size; i++) {
          Vertex child = arc.tails.get(i);
          double s = iScore[child.start][child.end][child.goal];
          childrenScores.add(s);
          score += s;
        }
        if (score == Double.NEGATIVE_INFINITY) { continue; }
        List<Integer> j = new ArrayList<Integer>();
        for (int i = 0; i < size; i++) {
          j.add(1);
        }
        Derivation d = new Derivation(arc, j, score, childrenScores);
        candV.add(d, score);
      }
      PriorityQueue<Derivation> tmp = new BinaryHeapPriorityQueue<Derivation>();
      for (int i = 0; i < k; i++) {
        if (candV.isEmpty()) { break; }
        Derivation d = candV.removeFirst();
        tmp.add(d, d.score);
      }
      candV = tmp;
      cand.put(v, candV);
    }
    return candV;
  }

  // note: kPrime is the original k
  private void lazyKthBest(Vertex v, int k, int kPrime) {
    PriorityQueue<Derivation> candV = getCandidates(v, kPrime);

    LinkedList<Derivation> dHatV = dHat.get(v);
    if (dHatV == null) {
      dHatV = new LinkedList<Derivation>();
      dHat.put(v,dHatV);
    }
    while (dHatV.size() < k) {
      if ( ! dHatV.isEmpty()) {
        Derivation derivation = dHatV.getLast();
        lazyNext(candV, derivation, kPrime);
      }
      if ( ! candV.isEmpty()) {
        Derivation d = candV.removeFirst();
        dHatV.add(d);
      } else {
        break;
      }
    }
  }

  private void lazyNext(PriorityQueue<Derivation> candV, Derivation derivation, int kPrime) {
    List<Vertex> tails = derivation.arc.tails;
    for  (int i = 0, sz = derivation.arc.size(); i < sz; i++) {
      List<Integer> j = new ArrayList<Integer>(derivation.j);
      j.set(i, j.get(i)+1);
      Vertex Ti = tails.get(i);
      lazyKthBest(Ti, j.get(i), kPrime);
      LinkedList<Derivation> dHatTi = dHat.get(Ti);
      // compute score for this derivation
      if (j.get(i)-1 >= dHatTi.size()) { continue; }
      Derivation d = dHatTi.get(j.get(i)-1);
      double newScore = derivation.score - derivation.childrenScores.get(i) + d.score;
      List<Double> childrenScores = new ArrayList<Double>(derivation.childrenScores);
      childrenScores.set(i, d.score);
      Derivation newDerivation = new Derivation(derivation.arc, j, newScore, childrenScores);
      if ( ! candV.contains(newDerivation) && newScore > Double.NEGATIVE_INFINITY) {
        candV.add(newDerivation, newScore);
      }
    }
  }

  //
  // END K-BEST STUFF
  //


  /** Get a complete set of the maximally scoring parses for a sentence,
   *  rather than one chosen at random.  This set may be of size 1 or larger.
   *
   *  @return All the equal best parses for a sentence, with each
   *         accompanied by its score
   */
  public List<ScoredObject<Tree>> getBestParses() {
    int start = 0;
    int end = length;
    int goal = stateNumberer.number(goalStr);
    double bestScore = iScore[start][end][goal];
    List<Tree> internalTrees = extractBestParses(goal, start, end);
    //System.out.println("Got internal best parse...");
    // for (Tree internalTree : internalTrees) {
    //   restoreUnaries(internalTree);
    // }
    //System.out.println("Restored unaries...");
    List<ScoredObject<Tree>> scoredTrees = new ArrayList<ScoredObject<Tree>>(internalTrees.size());
    for (Tree tr : internalTrees) {
      scoredTrees.add(new ScoredObject<Tree>(tr, bestScore));
    }
    return scoredTrees;
    //TreeTransformer debinarizer = BinarizerFactory.getDebinarizer();
    //return debinarizer.transformTree(internalTree);
  }


  public ExhaustivePCFGParser(BinaryGrammar bg, UnaryGrammar ug, Lexicon lex, Options op) {
    //    System.out.println("ExhaustivePCFGParser constructor called.");
    this.op = op;
    this.tlp = op.langpack();
    goalStr = tlp.startSymbol();
    this.stateSpace = bg.stateSpace();
    stateNumberer = Numberer.getGlobalNumberer(stateSpace);
    this.bg = bg;
    this.ug = ug;
    this.lex = lex;
    tf = new LabeledScoredTreeFactory(new StringLabelFactory());

    numStates = stateNumberer.total();
    isTag = new boolean[numStates];
    for (int state = 0; state < numStates; state++) {
      isTag[state] = tagNumberer.hasSeen(stateNumberer.object(state));
    }
  }


  public void nudgeDownArraySize() {
    try {
      if (arraySize > 2) {
        considerCreatingArrays(arraySize - 2);
      }
    } catch (OutOfMemoryError oome) {
      oome.printStackTrace();
    }
  }

  private void considerCreatingArrays(int length) {
    if (length > Test.maxLength + 1 || length >= myMaxLength) {
      throw new OutOfMemoryError("Refusal to create such large arrays.");
    } else {
      try {
        createArrays(length + 1);
      } catch (OutOfMemoryError e) {
        myMaxLength = length;
        if (arraySize > 0) {
          try {
            createArrays(arraySize);
          } catch (OutOfMemoryError e2) {
            throw new RuntimeException("CANNOT EVEN CREATE ARRAYS OF ORIGINAL SIZE!!");
          }
        }
        throw e;
      }
      arraySize = length + 1;
      if (Test.verbose) {
        System.err.println("Created PCFG parser arrays of size " + arraySize);
      }
    }
  }

  protected void createArrays(int length) {
    // zero out some stuff first in case we recently ran out of memory and are reallocating
    clearArrays();

    int numTags = tagNumberer.total();
    // allocate just the parts of iScore and oScore used (end > start, etc.)
    //    System.out.println("initializing iScore arrays with length " + length + " and numStates " + numStates);
    iScore = new float[length + 1][length + 1][];
    for (int start = 0; start <= length; start++) {
      for (int end = start + 1; end <= length; end++) {
        iScore[start][end] = new float[numStates];
      }
    }
    //    System.out.println("finished initializing iScore arrays");
    if (op.doDep && ! Test.useFastFactored) {
      //      System.out.println("initializing oScore arrays with length " + length + " and numStates " + numStates);
      oScore = new float[length + 1][length + 1][];
      for (int start = 0; start <= length; start++) {
        for (int end = start + 1; end <= length; end++) {
          oScore[start][end] = new float[numStates];
        }
      }
      //      System.out.println("finished initializing oScore arrays");
    }
    iPossibleByL = new boolean[length + 1][numStates];
    iPossibleByR = new boolean[length + 1][numStates];
    narrowRExtent = new int[length + 1][numStates];
    wideRExtent = new int[length + 1][numStates];
    narrowLExtent = new int[length + 1][numStates];
    wideLExtent = new int[length + 1][numStates];
    if (op.doDep && ! Test.useFastFactored) {
      oPossibleByL = new boolean[length + 1][numStates];
      oPossibleByR = new boolean[length + 1][numStates];

      oFilteredStart = new boolean[length + 1][numStates];
      oFilteredEnd = new boolean[length + 1][numStates];
    }
    tags = new boolean[length + 1][numTags];

    if (Test.lengthNormalization) {
      wordsInSpan = new int[length + 1][length + 1][];
      for (int start = 0; start <= length; start++) {
        for (int end = start + 1; end <= length; end++) {
          wordsInSpan[start][end] = new int[numStates];
        }
      }
    }
    //    System.out.println("ExhaustivePCFGParser constructor finished.");
  }

  private void clearArrays() {
    iScore = oScore = null;
    iPossibleByL = iPossibleByR = oFilteredEnd = oFilteredStart = oPossibleByL = oPossibleByR = tags = null;
    narrowRExtent = wideRExtent = narrowLExtent = wideLExtent = null;
  }

} // end class ExhaustivePCFGParser
