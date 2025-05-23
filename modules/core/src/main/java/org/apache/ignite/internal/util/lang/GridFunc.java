/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.util.lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import javax.cache.Cache;
import org.apache.ignite.cluster.BaselineNode;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.binary.BinaryArray;
import org.apache.ignite.internal.util.F0;
import org.apache.ignite.internal.util.GridCommonFunc;
import org.apache.ignite.internal.util.GridConcurrentHashSet;
import org.apache.ignite.internal.util.GridEmptyIterator;
import org.apache.ignite.internal.util.GridLeanMap;
import org.apache.ignite.internal.util.GridLeanSet;
import org.apache.ignite.internal.util.lang.gridfunc.AlwaysFalsePredicate;
import org.apache.ignite.internal.util.lang.gridfunc.AlwaysTruePredicate;
import org.apache.ignite.internal.util.lang.gridfunc.AlwaysTrueReducer;
import org.apache.ignite.internal.util.lang.gridfunc.CacheEntryGetValueClosure;
import org.apache.ignite.internal.util.lang.gridfunc.CacheEntryHasPeekPredicate;
import org.apache.ignite.internal.util.lang.gridfunc.ClusterNodeGetIdClosure;
import org.apache.ignite.internal.util.lang.gridfunc.ConcurrentHashSetFactoryCallable;
import org.apache.ignite.internal.util.lang.gridfunc.ConcurrentMapFactoryCallable;
import org.apache.ignite.internal.util.lang.gridfunc.ContainsNodeIdsPredicate;
import org.apache.ignite.internal.util.lang.gridfunc.EqualsClusterNodeIdPredicate;
import org.apache.ignite.internal.util.lang.gridfunc.EqualsUuidPredicate;
import org.apache.ignite.internal.util.lang.gridfunc.FlatCollectionWrapper;
import org.apache.ignite.internal.util.lang.gridfunc.FlatIterator;
import org.apache.ignite.internal.util.lang.gridfunc.HasEqualIdPredicate;
import org.apache.ignite.internal.util.lang.gridfunc.HasNotEqualIdPredicate;
import org.apache.ignite.internal.util.lang.gridfunc.IdentityClosure;
import org.apache.ignite.internal.util.lang.gridfunc.IsAllPredicate;
import org.apache.ignite.internal.util.lang.gridfunc.IsNotAllPredicate;
import org.apache.ignite.internal.util.lang.gridfunc.IsNotNullPredicate;
import org.apache.ignite.internal.util.lang.gridfunc.MultipleIterator;
import org.apache.ignite.internal.util.lang.gridfunc.NoOpClosure;
import org.apache.ignite.internal.util.lang.gridfunc.NotContainsPredicate;
import org.apache.ignite.internal.util.lang.gridfunc.NotEqualPredicate;
import org.apache.ignite.internal.util.lang.gridfunc.PredicateCollectionView;
import org.apache.ignite.internal.util.lang.gridfunc.PredicateMapView;
import org.apache.ignite.internal.util.lang.gridfunc.PredicateSetView;
import org.apache.ignite.internal.util.lang.gridfunc.ReadOnlyCollectionView;
import org.apache.ignite.internal.util.lang.gridfunc.ReadOnlyCollectionView2X;
import org.apache.ignite.internal.util.lang.gridfunc.SetFactoryCallable;
import org.apache.ignite.internal.util.lang.gridfunc.TransformCollectionView;
import org.apache.ignite.internal.util.lang.gridfunc.TransformFilteringIterator;
import org.apache.ignite.internal.util.lang.gridfunc.TransformMapView;
import org.apache.ignite.internal.util.typedef.internal.A;
import org.apache.ignite.lang.IgniteBiClosure;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.lang.IgniteClosure;
import org.apache.ignite.lang.IgnitePredicate;
import org.apache.ignite.lang.IgniteReducer;
import org.jetbrains.annotations.Nullable;

/**
 * Contains factory and utility methods for {@code closures}, {@code predicates}, and {@code tuples}.
 * It also contains functional style collection comprehensions.
 * <p>
 * Most of the methods in this class can be divided into two groups:
 * <ul>
 * <li><b>Factory</b> higher-order methods for closures, predicates and tuples, and</li>
 * <li><b>Utility</b> higher-order methods for processing collections with closures and predicates.</li>
 * </ul>
 * Note that contrary to the usual design this class has substantial number of
 * methods (over 200). This design is chosen to simulate a global namespace
 * (like a {@code Predef} in Scala) to provide general utility and functional
 * programming functionality in a shortest syntactical context using {@code F}
 * typedef.
 * <p>
 * Also note, that in all methods with predicates, null predicate has a {@code true} meaning. So does
 * the empty predicate array.
 */
@SuppressWarnings("unchecked")
public class GridFunc extends GridCommonFunc {
    /** */
    private static final GridAbsClosure NOOP = new NoOpClosure();

    /** */
    private static final IgniteClosure IDENTITY = new IdentityClosure();

    /** */
    private static final IgnitePredicate<Object> ALWAYS_TRUE = new AlwaysTruePredicate<>();

    /** */
    private static final IgnitePredicate<Object> ALWAYS_FALSE = new AlwaysFalsePredicate<>();

    /** */
    private static final IgnitePredicate<Object> IS_NOT_NULL = new IsNotNullPredicate();

    /** */
    private static final IgniteCallable<?> SET_FACTORY = new SetFactoryCallable();

    /** */
    private static final IgniteCallable<?> CONCURRENT_MAP_FACTORY = new ConcurrentMapFactoryCallable();

    /** */
    private static final IgniteCallable<?> CONCURRENT_SET_FACTORY = new ConcurrentHashSetFactoryCallable();

    /** */
    private static final IgniteClosure CACHE_ENTRY_VAL_GET = new CacheEntryGetValueClosure();

    /** */
    private static final IgnitePredicate CACHE_ENTRY_HAS_PEEK_VAL = new CacheEntryHasPeekPredicate();

    /** */
    private static final IgniteClosure<ClusterNode, UUID> NODE2ID = new ClusterNodeGetIdClosure();

    /** */
    private static final IgniteClosure<BaselineNode, Object> NODE2CONSISTENTID =
        BaselineNode::consistentId;

    /**
     * Gets predicate that evaluates to {@code true} only for given local node ID.
     *
     * @param locNodeId Local node ID.
     * @param <T> Type of the node.
     * @return Return {@code true} only for the node with given local node ID.
     */
    public static <T extends ClusterNode> IgnitePredicate<T> localNode(final UUID locNodeId) {
        return new HasEqualIdPredicate<>(locNodeId);
    }

    /**
     * Gets predicate that evaluates to {@code false} for given local node ID.
     *
     * @param locNodeId Local node ID.
     * @param <T> Type of the node.
     * @return Return {@code false} for the given local node ID.
     */
    public static <T extends ClusterNode> IgnitePredicate<T> remoteNodes(final UUID locNodeId) {
        return new HasNotEqualIdPredicate<>(locNodeId);
    }

    /**
     * Calculates sum of all elements.
     * <p>
     * <img src="{@docRoot}/img/sum.png">
     *
     * @param c Collection of elements.
     * @return Sum of all elements.
     */
    public static int sumInt(Iterable<Integer> c) {
        A.notNull(c, "c");

        int sum = 0;

        for (int t : c)
            sum += t;

        return sum;
    }

    /**
     * Gets reducer which always returns {@code true} from {@link org.apache.ignite.lang.IgniteReducer#collect(Object)}
     * method and passed in {@code element} from {@link org.apache.ignite.lang.IgniteReducer#reduce()} method.
     *
     * @param elem Element to return from {@link org.apache.ignite.lang.IgniteReducer#reduce()} method.
     * @param <T> Reducer element type.
     * @return Passed in element.
     */
    public static <T> IgniteReducer<T, T> identityReducer(final T elem) {
        return new AlwaysTrueReducer<>(elem);
    }

    /**
     * Convenient utility method that returns collection of node IDs for a given
     * collection of grid nodes.
     * <p>
     * Note that this method doesn't create a new collection but simply iterates
     * over the input one.
     *
     * @param nodes Collection of grid nodes.
     * @return Collection of node IDs for given collection of grid nodes.
     */
    public static Collection<UUID> nodeIds(@Nullable Collection<? extends ClusterNode> nodes) {
        if (nodes == null || nodes.isEmpty())
            return Collections.emptyList();

        return viewReadOnly(nodes, node2id());
    }

    /**
     * Convenient utility method that returns collection of node consistent IDs for a given
     * collection of grid nodes.
     * <p>
     * Note that this method doesn't create a new collection but simply iterates
     * over the input one.
     *
     * @param nodes Collection of grid nodes.
     * @return Collection of node consistent IDs for given collection of grid nodes.
     */
    public static Collection<Object> nodeConsistentIds(@Nullable Collection<? extends BaselineNode> nodes) {
        if (nodes == null || nodes.isEmpty())
            return Collections.emptyList();

        return viewReadOnly(nodes, NODE2CONSISTENTID);
    }

    /**
     * Gets random value from given collection.
     *
     * @param c Input collection (no {@code null} and not emtpy).
     * @param <T> Type of the collection.
     * @return Random value from the input collection.
     */
    public static <T> T rand(Collection<? extends T> c) {
        A.notNull(c, "c");

        int n = ThreadLocalRandom.current().nextInt(c.size());

        if (c instanceof List)
            return ((List<? extends T>)c).get(n);

        int i = 0;

        for (T t : c) {
            if (i++ == n)
                return t;
        }

        throw new ConcurrentModificationException();
    }

    /**
     * Gets random value from given list. For random-access lists this
     * operation is O(1), otherwise O(n).
     *
     * @param l Input collection.
     * @param <T> Type of the list elements.
     * @return Random value from the input list.
     */
    public static <T> T rand(List<T> l) {
        A.notNull(l, "l");

        return l.get(ThreadLocalRandom.current().nextInt(l.size()));
    }

    /**
     * Concatenates an element to a collection. If {@code copy} flag is {@code true}, then
     * a new collection will be created and the element and passed in collection will be
     * copied into the new one. The returned collection will be modifiable. If {@code copy}
     * flag is {@code false}, then a read-only view will be created over the element and given
     * collections and no copying will happen.
     *
     * @param cp Copy flag.
     * @param t First element.
     * @param c Second collection.
     * @param <T> Element type.
     * @return Concatenated collection.
     */
    public static <T> Collection<T> concat(boolean cp, @Nullable final T t, @Nullable final Collection<T> c) {
        if (cp) {
            if (isEmpty(c)) {
                Collection<T> l = new ArrayList<>(1);

                l.add(t);

                return l;
            }

            Collection<T> ret = new ArrayList<>(c.size() + 1);

            ret.add(t);
            ret.addAll(c);

            return ret;
        }
        else {
            if (isEmpty(c))
                return Collections.singletonList(t);

            return new ReadOnlyCollectionView<>(c, t);
        }
    }

    /**
     * Concatenates 2 collections into one. If {@code copy} flag is {@code true}, then
     * a new collection will be created and these collections will be copied into the
     * new one. The returned collection will be modifiable. If {@code copy} flag is
     * {@code false}, then a read-only view will be created over given collections
     * and no copying will happen.
     *
     * @param cp Copy flag.
     * @param c1 First collection.
     * @param c2 Second collection.
     * @param <T> Element type.
     * @return Concatenated {@code non-null} collection.
     */
    @SuppressWarnings("ConstantConditions")
    public static <T> Collection<T> concat(boolean cp, @Nullable final Collection<T> c1,
        @Nullable final Collection<T> c2) {
        if (cp) {
            if (isEmpty(c1) && isEmpty(c2))
                return new ArrayList<>(0);

            if (isEmpty(c1))
                return new ArrayList<>(c2);

            if (isEmpty(c2))
                return new ArrayList<>(c1);

            Collection<T> c = new ArrayList<>(c1.size() + c2.size());

            c.addAll(c1);
            c.addAll(c2);

            return c;
        }
        else {
            if (isEmpty(c1) && isEmpty(c2))
                return Collections.emptyList();

            if (isEmpty(c1) || isEmpty(c2)) {
                Collection<T> c = isEmpty(c1) ? c2 : c1;

                assert c != null;

                return c;
            }

            return new ReadOnlyCollectionView2X<>(c1, c2);
        }
    }

    /**
     * Concatenates an elements to an array.
     *
     * @param arr Array.
     * @param obj One or more elements.
     * @return Concatenated array.
     */
    public static <T> T[] concat(@Nullable T[] arr, T... obj) {
        T[] newArr;

        if (arr == null || arr.length == 0)
            newArr = obj;
        else {
            newArr = Arrays.copyOf(arr, arr.length + obj.length);

            System.arraycopy(obj, 0, newArr, arr.length, obj.length);
        }

        return newArr;
    }

    /**
     * Concatenates multiple iterators as single one.
     *
     * @param iters Iterators.
     * @return Single iterator.
     */
    @SuppressWarnings("unchecked")
    public static <T> Iterator<T> concat(Iterator<T>... iters) {
        if (iters.length == 1)
            return iters[0];

        return concat(asList(iters).iterator());
    }

    /**
     * Concatenates multiple iterators as single one.
     *
     * @param iters Iterator over iterators.
     * @return Single iterator.
     */
    public static <T> Iterator<T> concat(final Iterator<Iterator<T>> iters) {
        if (!iters.hasNext())
            return Collections.emptyIterator();

        return new MultipleIterator<>(iters);
    }

    /**
     * Loses all elements in input collection that are evaluated to {@code true} by
     * all given predicates.
     *
     * @param c Input collection.
     * @param cp If {@code true} method creates new collection without modifying the input one,
     *      otherwise does <tt>in-place</tt> modifications.
     * @param p Predicates to filter by. If no predicates provided - no elements are lost.
     * @param <T> Type of collections.
     * @return Collection of remaining elements.
     */
    public static <T> Collection<T> lose(Collection<T> c, boolean cp, @Nullable IgnitePredicate<? super T>... p) {
        A.notNull(c, "c");

        Collection<T> res;

        if (!cp) {
            res = c;

            if (isEmpty(p))
                res.clear();
            else if (!isAlwaysFalse(p))
                for (Iterator<T> iter = res.iterator(); iter.hasNext();)
                    if (isAll(iter.next(), p))
                        iter.remove();
        }
        else {
            res = new LinkedList<>();

            if (!isEmpty(p) && !isAlwaysTrue(p))
                for (T t : c)
                    if (!isAll(t, p))
                        res.add(t);
        }

        return res;
    }

    /**
     * Loses all elements in input list that are contained in {@code filter} collection.
     *
     * @param c Input list.
     * @param cp If {@code true} method creates new list not modifying input,
     *      otherwise does <tt>in-place</tt> modifications.
     * @param filter Filter collection. If {@code filter} collection is empty or
     *      {@code null} - no elements are lost.
     * @param <T> Type of list.
     * @return List of remaining elements
     */
    @SuppressWarnings("SuspiciousMethodCalls")
    public static <T> List<T> loseList(List<T> c, boolean cp, @Nullable Collection<? super T> filter) {
        A.notNull(c, "c");

        List<T> res;

        if (!cp) {
            res = c;

            if (filter != null)
                res.removeAll(filter);
        }
        else {
            res = new LinkedList<>();

            for (T t : c) {
                if (filter == null || !filter.contains(t))
                    res.add(t);
            }
        }

        return res;
    }

    /**
     * Gets closure which converts node to node ID.
     *
     * @return Closure which converts node to node ID.
     */
    public static IgniteClosure<? super ClusterNode, UUID> node2id() {
        return NODE2ID;
    }

    /**
     * Creates grid node predicate evaluating on the given node ID.
     *
     * @param nodeId Node ID for which returning predicate will evaluate to {@code true}.
     * @return Grid node predicate evaluating on the given node ID.
     * @see #idForNodeId(UUID)
     * @see #nodeIds(Collection)
     */
    public static <T extends ClusterNode> IgnitePredicate<T> nodeForNodeId(final UUID nodeId) {
        A.notNull(nodeId, "nodeId");

        return new EqualsClusterNodeIdPredicate<>(nodeId);
    }

    /**
     * Creates grid node predicate evaluating on the given node IDs.
     *
     * @param nodeIds Collection of node IDs.
     * @return Grid node predicate evaluating on the given node IDs.
     * @see #idForNodeId(UUID)
     * @see #nodeIds(Collection)
     */
    public static <T extends ClusterNode> IgnitePredicate<T> nodeForNodeIds(@Nullable final Collection<UUID>
        nodeIds) {
        if (isEmpty(nodeIds))
            return alwaysFalse();

        return new ContainsNodeIdsPredicate<>(nodeIds);
    }

    /**
     * Creates {@link UUID} predicate evaluating on the given node ID.
     *
     * @param nodeId Node ID for which returning predicate will evaluate to {@code true}.
     * @return {@link UUID} predicate evaluating on the given node ID.
     * @see #nodeForNodeId(UUID)
     * @see #nodeIds(Collection)
     */
    public static IgnitePredicate<UUID> idForNodeId(final UUID nodeId) {
        A.notNull(nodeId, "nodeId");

        return new EqualsUuidPredicate(nodeId);
    }

    /**
     * Creates predicates that evaluates to {@code true} for each node in given collection.
     * Note that if collection is empty the result predicate will always evaluate to {@code false}.
     * Implementation simply creates {@link GridNodePredicate} instance.
     *
     * @param nodes Collection of nodes. If none provided - result predicate will always
     *      return {@code false}.
     * @return Predicates that evaluates to {@code true} for each node in given collection.
     */
    public static IgnitePredicate<ClusterNode> nodeForNodes(ClusterNode... nodes) {
        return new GridNodePredicate(nodes);
    }

    /**
     * Retains all elements in input collection that are evaluated to {@code true}
     * by all given predicates.
     *
     * @param c Input collection.
     * @param cp If {@code true} method creates collection not modifying input, otherwise does
     *      <tt>in-place</tt> modifications.
     * @param p Predicates to filter by. If no predicates provides - all elements
     *      will be retained.
     * @param <T> Type of collections.
     * @return Collection of retain elements.
     */
    public static <T> Collection<T> retain(Collection<T> c, boolean cp, @Nullable IgnitePredicate<? super T>... p) {
        A.notNull(c, "c");

        return lose(c, cp, not(p));
    }

    /**
     * Converts array to {@link List}. Note that resulting list cannot
     * be altered in size, as it it based on the passed in array -
     * only current elements can be changed.
     * <p>
     * Note that unlike {@link Arrays#asList(Object[])}, this method is
     * {@code null}-safe. If {@code null} is passed in, then empty list
     * will be returned.
     *
     * @param vals Array of values
     * @param <T> Array type.
     * @return {@link List} instance for array.
     */
    public static <T> List<T> asList(@Nullable T... vals) {
        return isEmpty(vals) ? Collections.<T>emptyList() : Arrays.asList(vals);
    }

    /**
     * Creates new empty iterator.
     *
     * @param <T> Type of the iterator.
     * @return Newly created empty iterator.
     */
    public static <T> GridIterator<T> emptyIterator() {
        return new GridEmptyIterator<>();
    }

    /**
     * Flattens collection-of-collections and returns collection over the
     * elements of the inner collections. This method doesn't create any
     * new collections or copies any elements.
     * <p>
     * Note that due to non-copying nature of implementation, the
     * {@link Collection#size() size()} method of resulting collection will have to
     * iterate over all elements to produce size. Method {@link Collection#isEmpty() isEmpty()},
     * however, is constant time and is much more preferable to use instead
     * of {@code 'size()'} method when checking if list is not empty.
     *
     * @param c Input collection of collections.
     * @param <T> Type of the inner collections.
     * @return Iterable over the elements of the inner collections.
     */
    public static <T> Collection<T> flatCollections(@Nullable final Collection<? extends Collection<T>> c) {
        if (isEmpty(c))
            return Collections.emptyList();

        return new FlatCollectionWrapper<>(c);
    }

    /**
     * Flattens iterable-of-iterables and returns iterable over the
     * elements of the inner collections. This method doesn't create any
     * new collections or copies any elements.
     *
     * @param c Input collection of collections.
     * @param <T> Type of the inner collections.
     * @return Iterable over the elements of the inner collections.
     */
    public static <T> GridIterator<T> flat(@Nullable final Iterable<? extends Iterable<T>> c) {
        return isEmpty(c) ? GridFunc.<T>emptyIterator() : new FlatIterator<T>(c);
    }

    /**
     * Flattens iterable-of-iterators and returns iterator over the
     * elements of the inner collections. This method doesn't create any
     * new collections or copies any elements.
     *
     * @param c Input iterable of iterators.
     * @return Iterator over the elements of given iterators.
     */
    public static <T> Iterator<T> flatIterators(@Nullable final Iterable<Iterator<T>> c) {
        return isEmpty(c) ? GridFunc.<T>emptyIterator() : new FlatIterator<T>(c);
    }

    /**
     * Gets size of the given collection with provided optional predicates.
     *
     * @param c Collection to size.
     * @param p Optional predicates that filters out elements from count.
     * @param <T> Type of the iterator.
     * @return Number of elements in the collection for which all given predicates
     *      evaluates to {@code true}. If no predicates is provided - all elements are counted.
     */
    public static <T> int size(@Nullable Collection<? extends T> c, @Nullable IgnitePredicate<? super T>... p) {
        return c == null || c.isEmpty() ? 0 : isEmpty(p) || isAlwaysTrue(p) ? c.size() : size(c.iterator(), p);
    }

    /**
     * Gets size of the given iterator with provided optional predicates. Iterator
     * will be traversed to get the count.
     *
     * @param it Iterator to size.
     * @param p Optional predicates that filters out elements from count.
     * @param <T> Type of the iterator.
     * @return Number of elements in the iterator for which all given predicates
     *      evaluates to {@code true}. If no predicates is provided - all elements are counted.
     */
    public static <T> int size(@Nullable Iterator<? extends T> it, @Nullable IgnitePredicate<? super T>... p) {
        if (it == null)
            return 0;

        int n = 0;

        if (!isAlwaysFalse(p)) {
            while (it.hasNext()) {
                if (isAll(it.next(), p))
                    n++;
            }
        }

        return n;
    }

    /**
     * Creates write-through light-weight view on given collection with provided predicates. Resulting
     * collection will only "have" elements for which all provided predicates, if any, evaluate
     * to {@code true}. Note that only wrapping collection will be created and no duplication of
     * data will occur. Also note that if array of given predicates is not empty then method
     * {@code size()} uses full iteration through the collection.
     *
     * @param c Input collection that serves as a base for the view.
     * @param p Optional predicates. If predicates are not provided - all elements will be in the view.
     * @param <T> Type of the collection.
     * @return Light-weight view on given collection with provided predicate.
     */
    @SafeVarargs
    public static <T> Collection<T> view(@Nullable final Collection<T> c,
        @Nullable final IgnitePredicate<? super T>... p) {
        if (isEmpty(c) || isAlwaysFalse(p))
            return Collections.emptyList();

        return isEmpty(p) || isAlwaysTrue(p) ? c : new PredicateCollectionView<>(c, p);
    }

    /**
     * Creates read-only light-weight view on given collection with transformation and provided
     * predicates. Resulting collection will only "have" {@code transformed} elements for which
     * all provided predicate, if any, evaluates to {@code true}. Note that only wrapping
     * collection will be created and no duplication of data will occur. Also note that if array
     * of given predicates is not empty then method {@code size()} uses full iteration through
     * the collection.
     *
     * @param c Input collection that serves as a base for the view.
     * @param trans Transformation closure.
     * @param p Optional predicated. If predicates are not provided - all elements will be in the view.
     * @param <T1> Type of the collection.
     * @return Light-weight view on given collection with provided predicate.
     */
    @SafeVarargs
    public static <T1, T2> Collection<T2> viewReadOnly(@Nullable final Collection<? extends T1> c,
        final IgniteClosure<? super T1, T2> trans, @Nullable final IgnitePredicate<? super T1>... p) {
        A.notNull(trans, "trans");

        if (isEmpty(c) || isAlwaysFalse(p))
            return Collections.emptyList();

        return new TransformCollectionView<>(c, trans, p);
    }

    /**
     * Creates light-weight view on given map with provided predicates. Resulting map will
     * only "have" keys for which all provided predicates, if any, evaluates to {@code true}.
     * Note that only wrapping map will be created and no duplication of data will occur.
     * Also note that if array of given predicates is not empty then method {@code size()}
     * uses full iteration through the entry set.
     *
     * @param m Input map that serves as a base for the view.
     * @param p Optional predicates. If predicates are not provided - all will be in the view.
     * @param <K> Type of the key.
     * @param <V> Type of the value.
     * @return Light-weight view on given map with provided predicate.
     */
    public static <K0, K extends K0, V0, V extends V0> Map<K, V> view(@Nullable final Map<K, V> m,
        @Nullable final IgnitePredicate<? super K>... p) {
        if (isEmpty(m) || isAlwaysFalse(p))
            return Collections.emptyMap();

        return isEmpty(p) || isAlwaysTrue(p) ? m : new PredicateMapView<>(m, p);
    }

    /**
     * Read-only view on map that supports transformation of values and key filtering. Resulting map will
     * only "have" keys for which all provided predicates, if any, evaluates to {@code true}.
     * Note that only wrapping map will be created and no duplication of data will occur.
     * Also note that if array of given predicates is not empty then method {@code size()}
     * uses full iteration through the entry set.
     *
     * @param m Input map that serves as a base for the view.
     * @param trans Transformer for map value transformation.
     * @param p Optional predicates. If predicates are not provided - all will be in the view.
     * @param <K> Type of the key.
     * @param <V> Type of the input map value.
     * @param <V1> Type of the output map value.
     * @return Light-weight view on given map with provided predicate and transformer.
     */
    public static <K0, K extends K0, V0, V extends V0, V1> Map<K, V1> viewReadOnly(@Nullable final Map<K, V> m,
        final IgniteClosure<V, V1> trans, @Nullable final IgnitePredicate<? super K>... p) {
        A.notNull(trans, "trans");

        if (isEmpty(m) || isAlwaysFalse(p))
            return Collections.emptyMap();

        return new TransformMapView<>(m, trans, p);
    }

    /**
     * Read-only map view of a collection. Resulting map is a lightweight view of an input collection,
     * with filtered elements of an input collection as keys, and closure execution results
     * as values. The map will only contain keys for which all provided predicates, if any, evaluate
     * to {@code true}. Note that only wrapping map will be created and no duplication of data will occur.
     * Also note that if array of given predicates is not empty then method {@code size()}
     * uses full iteration through the entry set.
     *
     * @param c Input collection.
     * @param mapClo Mapping closure, that maps key to value.
     * @param p Optional predicates to filter input collection. If predicates are not provided - all
     *          elements will be in the view.
     * @param <K> Key type.
     * @param <V> Value type.
     * @return Light-weight view on given map with provided predicates and mapping.
     */
    public static <K0, K extends K0, V0, V extends V0> Map<K, V> viewAsMap(@Nullable final Set<K> c,
        final IgniteClosure<? super K, V> mapClo, @Nullable final IgnitePredicate<? super K>... p) {
        A.notNull(mapClo, "trans");

        if (isEmpty(c) || isAlwaysFalse(p))
            return Collections.emptyMap();

        return new PredicateSetView<>(c, mapClo, p);
    }

    /**
     * Returns a factory closure that creates new {@link Set} instance. Note that this
     * method does not create a new closure but returns a static one.
     *
     * @param <T> Type parameters for the created {@link Set}.
     * @return Factory closure that creates new {@link Set} instance every time
     *      its {@link org.apache.ignite.lang.IgniteOutClosure#apply()} method is called.
     */
    public static <T> IgniteCallable<Set<T>> newSet() {
        return (IgniteCallable<Set<T>>)SET_FACTORY;
    }

    /**
     * Returns a factory closure that creates new {@link ConcurrentMap} instance.
     * Note that this method does not create a new closure but returns a static one.
     *
     * @param <K> Type of the key for the created {@link ConcurrentMap}.
     * @param <V> Type of the value for the created {@link ConcurrentMap}.
     * @return Factory closure that creates new {@link Map} instance every
     *      time its {@link org.apache.ignite.lang.IgniteOutClosure#apply()} method is called.
     */
    public static <K, V> IgniteCallable<ConcurrentMap<K, V>> newCMap() {
        return (IgniteCallable<ConcurrentMap<K, V>>)CONCURRENT_MAP_FACTORY;
    }

    /**
     * Returns a factory closure that creates new {@link GridConcurrentHashSet} instance.
     * Note that this method does not create a new closure but returns a static one.
     *
     * @return Factory closure that creates new {@link GridConcurrentHashSet} instance every
     *      time its {@link org.apache.ignite.lang.IgniteOutClosure#apply()} method is called.
     */
    public static <E> IgniteCallable<Set<E>> newCSet() {
        return (IgniteCallable<Set<E>>)CONCURRENT_SET_FACTORY;
    }

    /**
     * Creates and returns iterator from given collection and optional filtering predicates.
     * Returned iterator will only have elements for which all given predicates evaluates to
     * {@code true} (if provided). Note that this method will not create new collection but
     * will simply "skip" elements in the provided collection that given predicates doesn't
     * evaluate to {@code true} for.
     *
     * @param c Input collection.
     * @param readOnly If {@code true}, then resulting iterator will not allow modifications
     *      to the underlying collection.
     * @param p Optional filtering predicates.
     * @param <T> Type of the collection elements.
     * @return Iterator from given collection and optional filtering predicate.
     */
    @SuppressWarnings({"unchecked"})
    public static <T> GridIterator<T> iterator0(Iterable<? extends T> c, boolean readOnly,
        IgnitePredicate<? super T>... p) {
        return iterator(c, IDENTITY, readOnly, p);
    }

    /**
     * Creates and returns transforming iterator from given collection and optional
     * filtering predicates. Returned iterator will only have elements for which all
     * given predicates evaluates to {@code true} ( if provided). Note that this method
     * will not create new collection but will simply "skip" elements in the provided
     * collection that given predicates doesn't evaluate to {@code true} for.
     *
     * @param c Input collection.
     * @param trans Transforming closure to convert from T1 to T2.
     * @param readOnly If {@code true}, then resulting iterator will not allow modifications
     *      to the underlying collection.
     * @param p Optional filtering predicates.
     * @param <T1> Type of the collection elements.
     * @param <T2> Type of returned elements.
     * @return Iterator from given collection and optional filtering predicate.
     */
    public static <T1, T2> GridIterator<T2> iterator(final Iterable<? extends T1> c,
        final IgniteClosure<? super T1, T2> trans, final boolean readOnly,
        @Nullable final IgnitePredicate<? super T1>... p) {
        A.notNull(c, "c", trans, "trans");

        if (isAlwaysFalse(p))
            return emptyIterator();

        return new TransformFilteringIterator<>(c.iterator(), trans, readOnly, p);
    }

    /**
     * @param c Input iterator.
     * @param trans Transforming closure to convert from T1 to T2.
     * @param readOnly If {@code true}, then resulting iterator will not allow modifications
     *      to the underlying collection.
     * @param p Optional filtering predicates.
     * @return Iterator from given iterator and optional filtering predicate.
     */
    @SafeVarargs
    public static <T1, T2> Iterator<T2> iterator(final Iterator<? extends T1> c,
        final IgniteClosure<? super T1, T2> trans,
        final boolean readOnly,
        @Nullable final IgnitePredicate<? super T1>... p
    ) {
        A.notNull(c, "c", trans, "trans");

        if (isAlwaysFalse(p))
            return emptyIterator();

        return new TransformFilteringIterator<>(c, trans, readOnly, p);
    }

    /**
     * Gets predicate that always returns {@code true}. This method returns
     * constant predicate.
     *
     * @param <T> Type of the free variable, i.e. the element the predicate is called on.
     * @return Predicate that always returns {@code true}.
     */
    public static <T> IgnitePredicate<T> alwaysTrue() {
        return (IgnitePredicate<T>)ALWAYS_TRUE;
    }

    /**
     * Gets predicate that always returns {@code false}. This method returns
     * constant predicate.
     *
     * @param <T> Type of the free variable, i.e. the element the predicate is called on.
     * @return Predicate that always returns {@code false}.
     */
    public static <T> IgnitePredicate<T> alwaysFalse() {
        return (IgnitePredicate<T>)ALWAYS_FALSE;
    }

    /**
     * Tests whether or not given predicate is the one returned from
     * {@link #alwaysTrue()} method.
     *
     * @param p Predicate to check.
     * @return {@code true} if given predicate is {@code ALWAYS_TRUE} predicate.
     */
    public static boolean isAlwaysTrue(IgnitePredicate p) {
        return p == ALWAYS_TRUE;
    }

    /**
     * Tests whether or not given set of predicates consists only of one predicate returned from
     * {@link #alwaysTrue()} method.
     *
     * @param p Predicate to check.
     * @return {@code true} if given contains only {@code ALWAYS_TRUE} predicate.
     */
    public static boolean isAlwaysTrue(@Nullable IgnitePredicate[] p) {
        return p != null && p.length == 1 && isAlwaysTrue(p[0]);
    }

    /**
     * Tests whether or not given predicate is the one returned from
     * {@link #alwaysFalse()} method.
     *
     * @param p Predicate to check.
     * @return {@code true} if given predicate is {@code ALWAYS_FALSE} predicate.
     */
    public static boolean isAlwaysFalse(IgnitePredicate p) {
        return p == ALWAYS_FALSE;
    }

    /**
     * Tests whether or not given set of predicates consists only of one predicate returned from
     * {@link #alwaysFalse()} method.
     *
     * @param p Predicate to check.
     * @return {@code true} if given contains only {@code ALWAYS_FALSE} predicate.
     */
    public static boolean isAlwaysFalse(@Nullable IgnitePredicate[] p) {
        return p != null && p.length == 1 && isAlwaysFalse(p[0]);
    }

    /**
     * Gets predicate that evaluates to {@code true} if its free variable is not {@code null}.
     *
     * @param <T> Type of the free variable, i.e. the element the predicate is called on.
     * @return Predicate that evaluates to {@code true} if its free variable is not {@code null}.
     */
    public static <T> IgnitePredicate<T> notNull() {
        return (IgnitePredicate<T>)IS_NOT_NULL;
    }

    /**
     * Negates given predicates.
     * <p>
     * Gets predicate that evaluates to {@code true} if any of given predicates
     * evaluates to {@code false}. If all predicates evaluate to {@code true} the
     * result predicate will evaluate to {@code false}.
     *
     * @param p Predicate to negate.
     * @param <T> Type of the free variable, i.e. the element the predicate is called on.
     * @return Negated predicate.
     */
    @SafeVarargs
    public static <T> IgnitePredicate<T> not(@Nullable final IgnitePredicate<? super T>... p) {
        return isAlwaysFalse(p) ? GridFunc.<T>alwaysTrue() : isAlwaysTrue(p) ? GridFunc.<T>alwaysFalse() : new IsNotAllPredicate<>(p);
    }

    /**
     * Gets predicate that evaluates to {@code true} if its free variable is not equal
     * to {@code target} or both are {@code null}.
     *
     * @param target Object to compare free variable to.
     * @param <T> Type of the free variable, i.e. the element the predicate is called on.
     * @return Predicate that evaluates to {@code true} if its free variable is not equal
     *      to {@code target} or both are {@code null}.
     */
    public static <T> IgnitePredicate<T> notEqualTo(@Nullable final T target) {
        return new NotEqualPredicate<>(target);
    }

    /**
     * Gets first element from given collection or returns {@code null} if the collection is empty.
     *
     * @param c A collection.
     * @param <T> Type of the collection.
     * @return Collections' first element or {@code null} in case if the collection is empty.
     */
    public static <T> T first(@Nullable Iterable<? extends T> c) {
        if (c == null)
            return null;

        if (c instanceof List)
            return first((List<? extends T>)c);

        Iterator<? extends T> it = c.iterator();

        return it.hasNext() ? it.next() : null;
    }

    /**
     * Gets first element from given list or returns {@code null} if list is empty.
     *
     * @param list List.
     * @return List' first element or {@code null} in case if list is empty.
     */
    public static <T> T first(List<? extends T> list) {
        if (list == null || list.isEmpty())
            return null;

        return list.get(0);
    }

    /**
     * Gets last element from given collection or returns {@code null} if the collection is empty.
     *
     * @param c A collection.
     * @param <T> Type of the collection.
     * @return Collections' first element or {@code null} in case if the collection is empty.
     */
    @Nullable public static <T> T last(@Nullable Iterable<? extends T> c) {
        if (c == null)
            return null;

        if (c instanceof RandomAccess && c instanceof List) {
            List<T> l = (List<T>)c;

            return l.get(l.size() - 1);
        }
        else if (c instanceof NavigableSet) {
            NavigableSet<T> s = (NavigableSet<T>)c;

            return s.last();
        }

        T last = null;

        for (T t : c)
            last = t;

        return last;
    }

    /**
     * Gets first value from given map or returns {@code null} if the map is empty.
     *
     * @param m A map.
     * @param <V> Value type.
     * @return Maps' first value or {@code null} in case if the map is empty.
     */
    @Nullable public static <V> V firstValue(Map<?, V> m) {
        Iterator<V> it = m.values().iterator();

        return it.hasNext() ? it.next() : null;
    }

    /**
     * Gets first entry from given map or returns {@code null} if the map is empty.
     *
     * @param m A map.
     * @param <K> Key type.
     * @param <V> Value type.
     * @return Map's first entry or {@code null} in case if the map is empty.
     */
    @Nullable public static <K, V> Map.Entry<K, V> firstEntry(Map<K, V> m) {
        Iterator<Map.Entry<K, V>> it = m.entrySet().iterator();

        return it.hasNext() ? it.next() : null;
    }

    /**
     * Get a predicate that evaluates to {@code true} if each of its component predicates
     * evaluates to {@code true}. The components are evaluated in order they are supplied.
     * Evaluation will be stopped as soon as first predicate evaluates to {@code false}.
     * Passed in predicates are NOT copied. If no predicates are passed in the returned
     * predicate will always evaluate to {@code false}.
     *
     * @param ps Passed in predicate. If none provided - always-{@code false} predicate is
     *      returned.
     * @param <T> Type of the free variable, i.e. the element the predicate is called on.
     * @return Predicate that evaluates to {@code true} if each of its component predicates
     *      evaluates to {@code true}.
     */
    @SuppressWarnings({"unchecked"})
    public static <T> IgnitePredicate<T> and(@Nullable final IgnitePredicate<? super T>... ps) {
        if (isEmpty(ps))
            return alwaysTrue();

        if (isAlwaysFalse(ps))
            return alwaysFalse();

        if (isAlwaysTrue(ps))
            return alwaysTrue();

        if (F0.isAllNodePredicates(ps)) {
            Set<UUID> ids = new HashSet<>();

            for (IgnitePredicate<? super T> p : ps) {
                if (p != null) {
                    Collection<UUID> list = ((GridNodePredicate)p).nodeIds();

                    if (ids.isEmpty())
                        ids.addAll(list);
                    else
                        ids.retainAll(list);
                }
            }

            // T must be <T extends ClusterNode>.
            return (IgnitePredicate<T>)new GridNodePredicate(ids);
        }
        else
            return new IsAllPredicate<>(ps);
    }

    /**
     * Gets identity closure, i.e. the closure that returns its variable value.
     *
     * @param <T> Type of the variable and return value for the closure.
     * @return Identity closure, i.e. the closure that returns its variable value.
     */
    @SuppressWarnings({"unchecked"})
    public static <T> IgniteClosure<T, T> identity() {
        return IDENTITY;
    }

    /**
     * Gets predicate that returns {@code true} if its free variable is not
     * contained in given collection.
     *
     * @param c Collection to check for containment.
     * @param <T> Type of the free variable for the predicate and type of the
     *      collection elements.
     * @return Predicate that returns {@code true} if its free variable is not
     *      contained in given collection.
     */
    public static <T> IgnitePredicate<T> notIn(@Nullable final Collection<? extends T> c) {
        return isEmpty(c) ? GridFunc.<T>alwaysTrue() : new NotContainsPredicate<>(c);
    }

    /**
     * Gets the value with given key. If that value does not exist, calls given
     * closure to get the default value, puts it into the map and returns it. If
     * closure is {@code null} return {@code null}.
     *
     * @param map Concurrent hash map.
     * @param key Key to get the value for.
     * @param c Default value producing closure.
     * @param <K> Key type.
     * @param <V> Value type.
     * @return Value for the key or the value produced by the closure if key
     *      does not exist in the map. Return {@code null} if key is not found and
     *      closure is {@code null}.
     */
    public static <K, V> V addIfAbsent(ConcurrentMap<K, V> map, K key, @Nullable Callable<V> c) {
        A.notNull(map, "map", key, "key");

        V v = map.get(key);

        if (v == null && c != null) {
            try {
                v = c.call();
            }
            catch (Exception e) {
                throw wrap(e);
            }

            V v0 = map.putIfAbsent(key, v);

            if (v0 != null)
                v = v0;
        }

        return v;
    }

    /**
     * Gets the value with given key. If that value does not exist, puts given
     * value into the map and returns it.
     *
     * @param map Map.
     * @param key Key.
     * @param val Value to put if one does not exist.
     * @param <K> Key type.
     * @param <V> Value type.
     * @return Current mapping for a given key.
     */
    public static <K, V> V addIfAbsent(ConcurrentMap<K, V> map, K key, V val) {
        A.notNull(map, "map", key, "key", val, "val");

        V v = map.putIfAbsent(key, val);

        if (v != null)
            val = v;

        return val;
    }

    /**
     * Utility map getter.
     *
     * @param map Map to get value from.
     * @param key Map key (can be {@code null}).
     * @param c Optional factory closure for the default value to be put in when {@code key}
     *      is not found. If closure is not provided - {@code null} will be put into the map.
     * @param <K> Map key type.
     * @param <V> Map value type.
     * @return Value for the {@code key} or default value produced by {@code c} if key is not
     *      found (or {@code null} if key is not found and closure is not provided). Note that
     *      in case when key is not found the default value will be put into the map.
     * @throws GridClosureException Thrown in case when callable throws exception.
     * @see #newSet()
     */
    @Nullable public static <K, V> V addIfAbsent(Map<? super K, V> map, @Nullable K key,
        @Nullable Callable<? extends V> c) {
        A.notNull(map, "map");

        try {
            if (!map.containsKey(key)) {
                V v = c == null ? null : c.call();

                map.put(key, v);

                return v;
            }
            else
                return map.get(key);
        }
        catch (Exception e) {
            throw wrap(e);
        }
    }

    /**
     * Utility map getter.
     *
     * @param map Map to get value from.
     * @param key Map key (can be {@code null}).
     * @param v Optional value to be put in when {@code key} is not found.
     *      If not provided - {@code null} will be put into the map.
     * @param <K> Map key type.
     * @param <V> Map value type.
     * @return Value for the {@code key} or default value {@code c} if key is not
     *      found (or {@code null} if key is not found and value is not provided). Note that
     *      in case when key is not found the default value will be put into the map.
     */
    @Nullable public static <K, V> V addIfAbsent(Map<K, V> map, @Nullable K key, @Nullable V v) {
        A.notNull(map, "map");

        try {
            if (!map.containsKey(key)) {
                map.put(key, v);

                return v;
            }
            else
                return map.get(key);
        }
        catch (Exception e) {
            throw wrap(e);
        }
    }

    /**
     * Transforms one collection to another using provided closure. New collection will be created.
     *
     * @param c Initial collection to transform.
     * @param f Closure to use for transformation.
     * @param <X> Type of the free variable for the closure and type of the collection elements.
     * @param <Y> Type of the closure's return value.
     * @return Transformed newly created collection.
     */
    public static <X, Y> Collection<Y> transform(Collection<? extends X> c, IgniteClosure<? super X, Y> f) {
        A.notNull(c, "c", f, "f");

        Collection<Y> d = new ArrayList<>(c.size());

        for (X x : c)
            d.add(f.apply(x));

        return d;
    }

    /**
     * Tests if all provided predicates evaluate to {@code true} for given value. Note that
     * evaluation will be short-circuit when first predicate evaluated to {@code false} is found.
     *
     * @param t Value to test.
     * @param p Optional set of predicates to use for evaluation. If no predicates provides
     *      this method will always return {@code true}.
     * @param <T> Type of the value and free variable of the predicates.
     * @return Returns {@code true} if given set of predicates is {@code null}, is empty, or all predicates
     *      evaluate to {@code true} for given value, {@code false} otherwise.
     */
    public static <T> boolean isAll(@Nullable T t, @Nullable IgnitePredicate<? super T>... p) {
        if (p != null)
            for (IgnitePredicate<? super T> r : p)
                if (r != null && !r.apply(t))
                    return false;

        return true;
    }

    /**
     * Tests if any of provided predicates evaluate to {@code true} for given value. Note
     * that evaluation will be short-circuit when first predicate evaluated to {@code true}
     * is found.
     *
     * @param t Value to test.
     * @param p Optional set of predicates to use for evaluation.
     * @param <T> Type of the value and free variable of the predicates.
     * @return Returns {@code true} if any of predicates evaluates to {@code true} for given
     *      value, {@code false} otherwise. Returns {@code false} if given set of predicates
     *      is {@code null} or empty.
     */
    public static <T> boolean isAny(@Nullable T t, @Nullable IgnitePredicate<? super T>... p) {
        if (p != null)
            for (IgnitePredicate<? super T> r : p)
                if (r != null && r.apply(t))
                    return true;

        return false;
    }

    /**
     * Creates an absolute (no-arg) closure that does nothing.
     *
     * @return Absolute (no-arg) closure that does nothing.
     */
    public static GridAbsClosure noop() {
        return NOOP;
    }

    /**
     * Finds and returns first element in given collection for which any of the
     * provided predicates evaluates to {@code true}.
     *
     * @param c Input collection.
     * @param dfltVal Default value to return when no element is found.
     * @param p Optional set of finder predicates.
     * @param <V> Type of the collection elements.
     * @return First element in given collection for which predicate evaluates to
     *      {@code true} - or {@code null} if such element cannot be found.
     */
    @SafeVarargs
    @Nullable public static <V> V find(Iterable<? extends V> c, @Nullable V dfltVal,
        @Nullable IgnitePredicate<? super V>... p) {
        A.notNull(c, "c");

        if (!isEmpty(p) && !isAlwaysFalse(p)) {
            for (V v : c) {
                if (isAny(v, p))
                    return v;
            }
        }

        return dfltVal;
    }

    /**
     * Checks if collection {@code c1} contains any elements from array {@code c2}.
     *
     * @param c1 Collection to check for containment. If {@code null} - this method returns {@code false}.
     * @param c2 Collection of elements to check. If {@code null} - this method returns {@code false}.
     * @param <T> Type of the elements.
     * @return {@code true} if collection {@code c1} contains at least one element from collection
     *      {@code c2}.
     */
    public static <T> boolean containsAny(@Nullable Collection<? extends T> c1, @Nullable T... c2) {
        if (c1 != null && !c1.isEmpty() && c2 != null && c2.length > 0)
            for (T t : c2)
                if (c1.contains(t))
                    return true;

        return false;
    }

    /**
     * Creates pair out of given two objects.
     *
     * @param t1 First object in pair.
     * @param t2 Second object in pair.
     * @param <T> Type of objects in pair.
     * @return Pair of objects.
     */
    public static <T> IgnitePair<T> pair(@Nullable T t1, @Nullable T t2) {
        return new IgnitePair<>(t1, t2);
    }

    /**
     * Checks for existence of the element in input collection for which all provided predicates
     * evaluate to {@code true}.
     *
     * @param c Input collection.
     * @param p Optional set of checking predicates.
     * @param <V> Type of the collection elements.
     * @return {@code true} if input collection contains element for which all the provided
     *      predicates evaluates to {@code true} - otherwise returns {@code false}.
     */
    public static <V> boolean exist(Iterable<? extends V> c, @Nullable IgnitePredicate<? super V>... p) {
        A.notNull(c, "c");

        if (isAlwaysFalse(p))
            return false;
        else if (isAlwaysTrue(p))
            return true;
        else if (isEmpty(p))
            return true;
        else
            for (V v : c)
                if (isAll(v, p))
                    return true;

        return false;
    }

    /**
     * Folds-right given collection using provided closure. If input collection contains <tt>a<sub>1</sub>,
     * a<sub>2</sub>, ..., a<sub>n</sub></tt> result value will be
     * <tt>...f(f(f(b,a<sub>1</sub>),a<sub>2</sub>),a<sub>3</sub>)...</tt>
     * where {@code f(x)} is the result of applying each closure from {@code fs} set to the
     * element {@code x} of the input collection {@code c}.
     * <p>
     * For example:
     * <pre name="code" class="java">
     * ...
     *
     * Collection&lt;Integer&gt; nums = new ArrayList&lt;Integer&gt;(size);
     *
     * // Search max value.
     * Integer max = F.fold(nums, F.first(nums), new C2&lt;Integer, Integer, Integer&gt;() {
     *     public Integer apply(Integer n, Integer max) { return Math.max(n, max); }
     * });
     *
     * ...
     * </pre>
     *
     * @param c Input collection.
     * @param b Optional first folding pair element.
     * @param fs Optional set of folding closures.
     * @param <D> Type of the input collection elements and type of the free variable for the closure.
     * @param <B> Type of the folding value and return type of the closure.
     * @return Value representing folded collection.
     */
    @Nullable public static <D, B> B fold(Iterable<? extends D> c, @Nullable B b,
        @Nullable IgniteBiClosure<? super D, ? super B, B>... fs) {
        A.notNull(c, "c");

        if (!isEmpty(fs))
            for (D e : c) {
                for (IgniteBiClosure<? super D, ? super B, B> f : fs)
                    b = f.apply(e, b);
            }

        return b;
    }

    /**
     * Converts collection to map using collection values as keys and
     * passed in default value as values.
     *
     * @param keys Map keys.
     * @param dfltVal Default value.
     * @param <K> Key type.
     * @param <V> Value type.
     * @return Resulting map.
     */
    public static <K, V> Map<K, V> zip(Collection<? extends K> keys, V dfltVal) {
        A.notNull(keys, "keys");

        Map<K, V> m = new HashMap<>(keys.size(), 1.0f);

        for (K k : keys)
            m.put(k, dfltVal);

        return m;
    }

    /**
     * Creates map with given values.
     *
     * @param k Key.
     * @param v Value.
     * @param <K> Key's type.
     * @param <V> Value's type.
     * @return Created map.
     */
    public static <K, V> Map<K, V> asMap(K k, V v) {
        Map<K, V> map = new GridLeanMap<>(1);

        map.put(k, v);

        return map;
    }

    /**
     * Creates map with given values.
     *
     * @param k1 Key 1.
     * @param v1 Value 1.
     * @param k2 Key 2.
     * @param v2 Value 2.
     * @param <K> Key's type.
     * @param <V> Value's type.
     * @return Created map.
     */
    public static <K, V> Map<K, V> asMap(K k1, V v1, K k2, V v2) {
        Map<K, V> map = new GridLeanMap<>(2);

        map.put(k1, v1);
        map.put(k2, v2);

        return map;
    }

    /**
     * Creates map with given values.
     *
     * @param k1 Key 1.
     * @param v1 Value 1.
     * @param k2 Key 2.
     * @param v2 Value 2.
     * @param k3 Key 3.
     * @param v3 Value 3.
     * @param <K> Key's type.
     * @param <V> Value's type.
     * @return Created map.
     */
    public static <K, V> Map<K, V> asMap(K k1, V v1, K k2, V v2, K k3, V v3) {
        Map<K, V> map = new GridLeanMap<>(3);

        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);

        return map;
    }

    /**
     * Creates map with given values.
     *
     * @param k1 Key 1.
     * @param v1 Value 1.
     * @param k2 Key 2.
     * @param v2 Value 2.
     * @param k3 Key 3.
     * @param v3 Value 3.
     * @param k4 Key 4.
     * @param v4 Value 4.
     * @param <K> Key's type.
     * @param <V> Value's type.
     * @return Created map.
     */
    public static <K, V> Map<K, V> asMap(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        Map<K, V> map = new GridLeanMap<>(4);

        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);

        return map;
    }

    /**
     * Creates map with given values.
     *
     * @param k1 Key 1.
     * @param v1 Value 1.
     * @param k2 Key 2.
     * @param v2 Value 2.
     * @param k3 Key 3.
     * @param v3 Value 3.
     * @param k4 Key 4.
     * @param v4 Value 4.
     * @param k5 Key 5.
     * @param v5 Value 5.
     * @param <K> Key's type.
     * @param <V> Value's type.
     * @return Created map.
     */
    public static <K, V> Map<K, V> asMap(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
        Map<K, V> map = new GridLeanMap<>(5);

        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        map.put(k5, v5);

        return map;
    }

    /**
     * Convenience method to convert multiple elements into array.
     *
     * @param t Elements to convert into array.
     * @param <T> Element type.
     * @return Array of elements.
     */
    public static <T> T[] asArray(T... t) {
        return t;
    }

    /**
     * Creates read-only list with given values.
     *
     * @param t Element (if {@code null}, then empty list is returned).
     * @param <T> Element's type.
     * @return Created list.
     */
    public static <T> List<T> asList(@Nullable T t) {
        return t == null ? Collections.<T>emptyList() : Collections.singletonList(t);
    }

    /**
     * Creates read-only set with given value.
     *
     * @param t Element (if {@code null}, then empty set is returned).
     * @param <T> Element's type.
     * @return Created set.
     */
    public static <T> Set<T> asSet(@Nullable T t) {
        return t == null ? Collections.<T>emptySet() : Collections.singleton(t);
    }

    /**
     * Creates read-only set with given values.
     *
     * @param t Element (if {@code null}, then empty set is returned).
     * @param <T> Element's type.
     * @return Created set.
     */
    @SuppressWarnings({"RedundantTypeArguments"})
    public static <T> Set<T> asSet(@Nullable T... t) {
        if (t == null || t.length == 0)
            return Collections.<T>emptySet();

        if (t.length == 1)
            return Collections.singleton(t[0]);

        return new GridLeanSet<>(asList(t));
    }

    /**
     * Checks if value is contained in the collection passed in. If the collection
     * is {@code null}, then {@code false} is returned.
     *
     * @param c Collection to check.
     * @param t Value to check for containment.
     * @param <T> Value type.
     * @return {@code True} if collection is not {@code null} and contains given
     *      value, {@code false} otherwise.
     */
    public static <T> boolean contains(@Nullable Collection<T> c, @Nullable T t) {
        return c != null && c.contains(t);
    }

    /**
     * Provides predicate which returns {@code true} if it receives an element
     * that is not contained in the passed in collection.
     *
     * @param c Collection used for predicate filter.
     * @param <T> Element type.
     * @return Predicate which returns {@code true} if it receives an element
     *  that is not contained in the passed in collection.
     */
    public static <T> IgnitePredicate<T> notContains(@Nullable final Collection<T> c) {
        return c == null || c.isEmpty() ? GridFunc.<T>alwaysTrue() : new NotContainsPredicate(c);
    }

    /**
     * @param arr Array.
     * @param val Value to find.
     * @return {@code True} if array contains given value.
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    public static boolean contains(int[] arr, int val) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == val)
                return true;
        }

        return false;
    }

    /**
     * Checks if key is contained in the map passed in. If the map
     * is {@code null}, then {@code false} is returned.
     *
     * @param m Map to check.
     * @param k Key to check for containment.
     * @param <T> Key type.
     * @return {@code true} if map is not {@code null} and contains given
     *      key, {@code false} otherwise.
     */
    public static <T> boolean mapContainsKey(@Nullable final Map<T, ?> m, final T k) {
        return m != null && m.containsKey(k);
    }

    /**
     * @param arr Array.
     * @param val Value to find.
     * @return {@code True} if array contains given value.
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    public static boolean contains(long[] arr, long val) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == val)
                return true;
        }

        return false;
    }

    /**
     * Tests whether specified arguments are equal, or both {@code null}.
     *
     * @param o1 Object to compare.
     * @param o2 Object to compare.
     * @return Returns {@code true} if the specified arguments are equal, or both {@code null}.
     */
    public static boolean eq(@Nullable Object o1, @Nullable Object o2) {
        return o1 == null ? o2 == null : o2 != null && (o1 == o2 || o1.equals(o2));
    }

    /**
     * Checks if both collections have equal elements in the same order.
     *
     * @param c1 First collection.
     * @param c2 Second collection.
     * @return {@code True} if both collections have equal elements in the same order.
     */
    public static boolean eqOrdered(@Nullable Collection<?> c1, @Nullable Collection<?> c2) {
        if (c1 == c2)
            return true;

        if (c1 == null || c2 == null)
            return false;

        if (c1.size() != c2.size())
            return false;

        Iterator<?> it1 = c1.iterator();
        Iterator<?> it2 = c2.iterator();

        while (it1.hasNext() && it2.hasNext())
            if (!Objects.equals(it1.next(), it2.next()))
                return false;

        return it1.hasNext() == it2.hasNext();
    }

    /**
     * Checks if both collections have equal elements in any order.
     * <p>
     * Implementation of this method takes optimization steps based on the actual type
     * of the collections. Dups are allowed in the collection. Note that if collections
     * allow fixed-time random access (implementing {@link RandomAccess} marker interface
     * - having them pre-sorted dramatically improves the performance.
     *
     * @param c1 First collection.
     * @param c2 Second collection.
     * @return {@code True} if both collections have equal elements in any order.
     */
    public static boolean eqNotOrdered(@Nullable Collection<?> c1, @Nullable Collection<?> c2) {
        if (c1 == c2)
            return true;

        if (c1 == null || c2 == null)
            return false;

        if (c1.size() != c2.size())
            return false;

        // Random index access is fixed time (at least for one), dups are allowed.
        // Optimize to 'n * log(n)' complexity. Pre-sorting of random accessed collection
        // helps HUGE as it will reduce complexity to 'n'.
        if (c1 instanceof RandomAccess || c2 instanceof RandomAccess) {
            Collection<?> col;
            List<?> lst;

            if (c1 instanceof RandomAccess) {
                col = c2;
                lst = (List<?>)c1;
            }
            else {
                col = c1;
                lst = (List<?>)c2;
            }

            int p = 0;

            // Collections are of the same size.
            int size = c1.size();

            for (Object o1 : col) {
                boolean found = false;

                for (int i = p; i < size; i++) {
                    if (Objects.equals(lst.get(i), o1)) {
                        found = true;

                        if (i == p)
                            p++;

                        break;
                    }
                }

                if (!found)
                    return false;
            }
        }
        else if (c1 instanceof Set && c2 instanceof Set) {
            // Sets don't have dups - we can skip cross checks.
            // Access is fixed-time - can do 'k*n' complexity.
            for (Object o : c1)
                if (!c2.contains(o))
                    return false;
        }
        // Non-sets, non random access and collections of different types
        // will do full cross-checks of '2k*n*n' complexity.
        else {
            // Dups are possible and no fixed-time random access...
            // Have to do '2k*n*n' complexity in worst case.
            for (Object o : c1)
                if (!c2.contains(o))
                    return false;

            for (Object o : c2)
                if (!c1.contains(o))
                    return false;

        }

        return true;
    }

    /**
     * Compares two maps. Unlike {@code java.util.AbstractMap#equals(...)} method this implementation
     * checks not only entry sets, but also the keys. Some optimization checks are also used.
     *
     * @param m1 First map to check.
     * @param m2 Second map to check
     * @return {@code True} is maps are equal, {@code False} otherwise.
     */
    public static <K, V> boolean eqNotOrdered(@Nullable Map<K, V> m1, @Nullable Map<K, V> m2) {
        if (m1 == m2)
            return true;

        if (m1 == null || m2 == null)
            return false;

        if (m1.size() != m2.size())
            return false;

        for (Map.Entry<K, V> e : m1.entrySet()) {
            V v1 = e.getValue();
            V v2 = m2.get(e.getKey());

            if (v1 == v2)
                continue;

            if (v1 == null || v2 == null)
                return false;

            if (v1 instanceof Collection && v2 instanceof Collection) {
                if (!eqNotOrdered((Collection)v1, (Collection)v2))
                    return false;
            }
            else {
                if (v1 instanceof Map && v2 instanceof Map) {
                    if (!eqNotOrdered((Map)v1, (Map)v2))
                        return false;
                }
                else {
                    if (!(v1.getClass().isArray() ? arrayEq(v1, v2) : Objects.equals(v1, v2)))
                        return false;
                }
            }
        }

        return true;
    }

    /**
     * Compares two {@link org.apache.ignite.cluster.ClusterNode} instances for equality.
     * <p>
     * Since introduction of {@link org.apache.ignite.cluster.ClusterNode} in Apache Ignite 3.0 the semantic of equality between
     * grid nodes has changed. Since rich node wraps thin node instance and in the same time
     * implements {@link org.apache.ignite.cluster.ClusterNode} interface, the proper semantic of comparing two grid node is
     * to ignore their runtime types and compare only by their IDs. This method implements this logic.
     * <p>
     * End users rarely, if ever, need to directly compare two grid nodes for equality. This method is
     * intended primarily for discovery SPI developers that provide implementations of {@link org.apache.ignite.cluster.ClusterNode}
     * interface.
     *
     * @param n1 Grid node 1.
     * @param n2 Grid node 2
     * @return {@code true} if two grid node have the same IDs (ignoring their runtime types),
     *      {@code false} otherwise.
     */
    public static boolean eqNodes(Object n1, Object n2) {
        return n1 == n2 || !(n1 == null || n2 == null) && !(!(n1 instanceof ClusterNode) || !(n2 instanceof ClusterNode))
            && ((ClusterNode)n1).id().equals(((ClusterNode)n2).id());
    }

    /**
     * Gets closure that returns value for an entry. The closure internally
     * delegates to {@link javax.cache.Cache.Entry#get(Object)} method.
     *
     * @param <K> Key type.
     * @param <V> Value type.
     * @return Closure that returns value for an entry.
     */
    public static <K, V> IgniteClosure<Cache.Entry<K, V>, V> cacheEntry2Get() {
        return (IgniteClosure<Cache.Entry<K, V>, V>)CACHE_ENTRY_VAL_GET;
    }

    /**
     * Gets predicate which returns {@code true} if entry has peek value.
     *
     * @param <K> Cache key type.
     * @param <V> Cache value type.
     * @return Predicate which returns {@code true} if entry has peek value.
     */
    public static <K, V> IgnitePredicate<Cache.Entry<K, V>> cacheHasPeekValue() {
        return (IgnitePredicate<Cache.Entry<K, V>>)CACHE_ENTRY_HAS_PEEK_VAL;
    }

    /**
     * Shortcut method that creates an instance of {@link GridClosureException}.
     *
     * @param e Exception to wrap.
     * @return Newly created instance of {@link GridClosureException}.
     */
    public static GridClosureException wrap(Throwable e) {
        return new GridClosureException(e);
    }

    /**
     * @param arr Array to check.
     * @return {@code True} if array sorted, {@code false} otherwise.
     */
    public static boolean isSorted(long[] arr) {
        if (isEmpty(arr) || arr.length == 1)
            return true;

        for (int i = 1; i < arr.length; i++) {
            if (arr[i - 1] > arr[i])
                return false;
        }

        return true;
    }

    /**
     * @param arr Array to check.
     * @return {@code True} if array sorted, {@code false} otherwise.
     */
    public static boolean isSorted(int[] arr) {
        if (isEmpty(arr) || arr.length == 1)
            return true;

        for (int i = 1; i < arr.length; i++) {
            if (arr[i - 1] > arr[i])
                return false;
        }

        return true;
    }

    /**
     * @param val Value to check.
     * @return {@code True} if not null and array.
     */
    public static boolean isArray(Object val) {
        return val != null && val.getClass().isArray();
    }

    /**
     * Check for arrays equality.
     *
     * @param a1 Value 1.
     * @param a2 Value 2.
     * @return {@code True} if arrays equal.
     */
    public static boolean arrayEq(Object a1, Object a2) {
        if (a1 == a2)
            return true;

        if (a1 == null || a2 == null)
            return a1 != null || a2 != null;

        if (a1.getClass() != a2.getClass())
            return false;

        if (a1 instanceof byte[])
            return Arrays.equals((byte[])a1, (byte[])a2);
        else if (a1 instanceof boolean[])
            return Arrays.equals((boolean[])a1, (boolean[])a2);
        else if (a1 instanceof short[])
            return Arrays.equals((short[])a1, (short[])a2);
        else if (a1 instanceof char[])
            return Arrays.equals((char[])a1, (char[])a2);
        else if (a1 instanceof int[])
            return Arrays.equals((int[])a1, (int[])a2);
        else if (a1 instanceof long[])
            return Arrays.equals((long[])a1, (long[])a2);
        else if (a1 instanceof float[])
            return Arrays.equals((float[])a1, (float[])a2);
        else if (a1 instanceof double[])
            return Arrays.equals((double[])a1, (double[])a2);
        else if (a1 instanceof BinaryArray)
            return a1.equals(a2);

        return Arrays.deepEquals((Object[])a1, (Object[])a2);
    }

    /**
     * Compare arrays.
     *
     * @param a1 Value 1.
     * @param a2 Value 2.
     * @return a negative integer, zero, or a positive integer as the first argument is less than, equal to,
     * or greater than the second.
     */
    public static int compareArrays(Object a1, Object a2) {
        if (a1 == a2)
            return 0;

        if (a1 == null || a2 == null)
            return a1 != null ? 1 : -1;

        if (a1.getClass() != a2.getClass()) {
            throw new IllegalArgumentException(
                "Can't compare arrays of different types[a1=" + a1.getClass() + ",a2=" + a2.getClass() + ']'
            );
        }

        if (a1 instanceof byte[])
            return compareArrays((byte[])a1, (byte[])a2);
        else if (a1 instanceof boolean[])
            return compareArrays((boolean[])a1, (boolean[])a2);
        else if (a1 instanceof short[])
            return compareArrays((short[])a1, (short[])a2);
        else if (a1 instanceof char[])
            return compareArrays((char[])a1, (char[])a2);
        else if (a1 instanceof int[])
            return compareArrays((int[])a1, (int[])a2);
        else if (a1 instanceof long[])
            return compareArrays((long[])a1, (long[])a2);
        else if (a1 instanceof float[])
            return compareArrays((float[])a1, (float[])a2);
        else if (a1 instanceof double[])
            return compareArrays((double[])a1, (double[])a2);
        else if (a1 instanceof Object[])
            return compareArrays((Object[])a1, (Object[])a2);

        throw new IllegalStateException("Unknown array type " + a1.getClass());
    }

    /** Compare arrays. */
    public static int compareArrays(Object[] a1, Object[] a2) {
        if (a1 == a2)
            return 0;

        int l = Math.min(a1.length, a2.length);

        for (int i = 0; i < l; i++) {
            if (a1[i] == null || a2[i] == null) {
                if (a1[i] != null || a2[i] != null)
                    return a1[i] != null ? 1 : -1;

                continue;
            }

            if (isArray(a1[i]) && isArray(a2[i])) {
                int res = compareArrays(a1[i], a2[i]);

                if (res != 0)
                    return res;
            }

            return ((Comparable)a1[i]).compareTo(a2[i]);
        }

        return Integer.compare(a1.length, a2.length);
    }

    /** Compare arrays. */
    public static int compareArrays(byte[] a1, byte[] a2) {
        if (a1 == a2)
            return 0;

        int l = Math.min(a1.length, a2.length);

        for (int i = 0; i < l; i++) {
            if (a1[i] != a2[i])
                return Byte.compare(a1[i], a2[i]);
        }

        return Integer.compare(a1.length, a2.length);
    }

    /** Compare arrays. */
    public static int compareArrays(boolean[] a1, boolean[] a2) {
        if (a1 == a2)
            return 0;

        int l = Math.min(a1.length, a2.length);

        for (int i = 0; i < l; i++) {
            if (a1[i] != a2[i])
                return Boolean.compare(a1[i], a2[i]);
        }

        return Integer.compare(a1.length, a2.length);
    }

    /** Compare arrays. */
    public static int compareArrays(short[] a1, short[] a2) {
        if (a1 == a2)
            return 0;

        int l = Math.min(a1.length, a2.length);

        for (int i = 0; i < l; i++) {
            if (a1[i] != a2[i])
                return Short.compare(a1[i], a2[i]);
        }

        return Integer.compare(a1.length, a2.length);
    }

    /** Compare arrays. */
    public static int compareArrays(char[] a1, char[] a2) {
        if (a1 == a2)
            return 0;

        int l = Math.min(a1.length, a2.length);

        for (int i = 0; i < l; i++) {
            if (a1[i] != a2[i])
                return Character.compare(a1[i], a2[i]);
        }

        return Integer.compare(a1.length, a2.length);
    }

    /** Compare arrays. */
    public static int compareArrays(int[] a1, int[] a2) {
        if (a1 == a2)
            return 0;

        int l = Math.min(a1.length, a2.length);

        for (int i = 0; i < l; i++) {
            if (a1[i] != a2[i])
                return Integer.compare(a1[i], a2[i]);
        }

        return Integer.compare(a1.length, a2.length);
    }

    /** Compare arrays. */
    public static int compareArrays(long[] a1, long[] a2) {
        if (a1 == a2)
            return 0;

        int l = Math.min(a1.length, a2.length);

        for (int i = 0; i < l; i++) {
            if (a1[i] != a2[i])
                return Long.compare(a1[i], a2[i]);
        }

        return Integer.compare(a1.length, a2.length);
    }

    /** Compare arrays. */
    public static int compareArrays(float[] a1, float[] a2) {
        if (a1 == a2)
            return 0;

        int l = Math.min(a1.length, a2.length);
        int res;

        for (int i = 0; i < l; i++) {
            if ((res = Float.compare(a1[i], a2[i])) != 0)
                return res;
        }

        return Integer.compare(a1.length, a2.length);
    }

    /** Compare arrays. */
    public static int compareArrays(double[] a1, double[] a2) {
        if (a1 == a2)
            return 0;

        int l = Math.min(a1.length, a2.length);
        int res;

        for (int i = 0; i < l; i++) {
            if ((res = Double.compare(a1[i], a2[i])) != 0)
                return res;
        }

        return Integer.compare(a1.length, a2.length);
    }
}
