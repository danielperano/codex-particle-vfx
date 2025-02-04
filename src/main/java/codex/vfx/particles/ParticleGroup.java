package codex.vfx.particles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import com.jme3.scene.Node;
import com.jme3.scene.Spatial;

import codex.vfx.VirtualEffect;
import codex.vfx.annotations.VfxAttribute;
import codex.vfx.annotations.VfxCommand;
import codex.vfx.annotations.VfxInfo;
import codex.vfx.particles.drivers.ParticleDriver;
import codex.vfx.particles.drivers.emission.EmissionPoint;
import codex.vfx.particles.drivers.emission.EmissionVolume;
import codex.vfx.particles.geometry.ParticleGeometry;

/**
 * Contains a group of particles.
 * 
 * @author codex
 * @param <T> channel of particle data this group uses
 */
public class ParticleGroup<T extends Particle> extends Node implements VirtualEffect, Iterable<T> {

    private ParticleGroup<T> parentGroup;
    private final LinkedList<ParticleGroup> childGroups = new LinkedList<>();
    private final ArrayList<T> particles = new ArrayList<>();
    private final LinkedList<ParticleDriver<T>> drivers = new LinkedList<>();
    private OverflowStrategy<T> overflow = OverflowStrategy.CullNew;
    private EmissionVolume volume = new EmissionPoint();
    private int capacity;
    private int dynamicSizingStep = -1;
    private float updateSpeed = 1f, decay = 1f;
    private float time = 0f, delay = 0f;
    private boolean playing = true, worldPlayState = true;
    private boolean inheritDecayRate = false;

    /**
     * Creates a particle group with zero capacity.
     */
    public ParticleGroup() {
        this(ParticleGroup.class.getSimpleName(), 0);
    }

    /**
     * Creates a particle group with zero capacity.
     * 
     * @param name name of this spatial
     */
    public ParticleGroup(String name) {
        this(name, 0);
    }

    /**
     * Creates a particle group with the given capacity.
     * 
     * @param capacity
     */
    public ParticleGroup(int capacity) {
        this(ParticleGroup.class.getSimpleName(), capacity);
    }

    /**
     * Creates a particle group with the given capacity.
     * 
     * @param name     name of this spatial
     * @param capacity
     */
    public ParticleGroup(String name, int capacity) {
        super(name);
        assert capacity >= 0 : "Group capacity must be greater than or equal to zero.";
        this.capacity = capacity;
    }

    @Override
    public void updateLogicalState(float tpf) {
        super.updateLogicalState(tpf);
        if (parentGroup == null) {
            update(playing, tpf, 1);
        }
    }

    @Override
    public int attachChildAt(Spatial spatial, int index) {
        if (spatial instanceof ParticleGroup) {
            ParticleGroup g = (ParticleGroup) spatial;
            g.parentGroup = this;
            childGroups.addLast(g);
        }
        return super.attachChildAt(spatial, index);
    }

    @Override
    public Spatial detachChildAt(int index) {
        Spatial spatial = super.detachChildAt(index);
        if (spatial instanceof ParticleGroup) {
            ParticleGroup g = (ParticleGroup) spatial;
            if (g.parentGroup == this) {
                g.parentGroup = null;
                childGroups.remove(g);
            }
        }
        return spatial;
    }

    /**
     * Updates this group and child groups.
     * 
     * @param update true if a regular update should be performed
     * @param tpf    propagated manipulated time per frame
     * @param decay  propagated decay rate
     */
    protected void update(boolean update, float tpf, float decay) {
        validateGroupSize(true);
        worldPlayState = update && playing;
        float t = tpf * updateSpeed;
        if (worldPlayState) {
            time += t;
        }
        if (inDelayZone()) {
            t = 0;
        }
        decay *= this.decay;
        if (worldPlayState) {
            updateParticles(t, (inheritDecayRate ? decay : this.decay));
        }
        // update child groups
        for (ParticleGroup g : childGroups) {
            g.update(worldPlayState, t, decay);
        }
    }

    protected void updateParticles(float tpf, float decay) {
        for (ParticleDriver<T> d : drivers) {
            d.updateGroup(this, tpf);
        }
        if (!particles.isEmpty())
            for (Iterator<T> it = particles.iterator(); it.hasNext();) {
                T p = it.next();
                for (ParticleDriver<T> d : drivers) {
                    d.updateParticle(p, tpf);
                }
                if (!p.update(tpf * decay)) {
                    it.remove();
                }
            }
    }

    protected boolean addParticle(T particle) {
        if (capacity > 0 && worldPlayState && particles.add(particle)) {
            if (particles.size() > capacity) {
                if (dynamicSizingStep >= 0) {
                    capacity = particles.size() + dynamicSizingStep;
                } else if (overflow.removeParticle(this, particle)) {
                    return false;
                }
            }
            for (ParticleDriver<T> d : drivers) {
                d.particleAdded(this, particle);
            }
            return true;
        }
        return false;
    }

    protected boolean validateGroupSize(boolean exception) {
        if (particles.size() > capacity) {
            if (exception) {
                throw new IllegalStateException("Internal Error: number of particles exceeded the group capacity.");
            }
            return false;
        }
        return true;
    }

    /**
     * Adds a particle to this group.
     * <p>
     * The group must be playing (true by default).
     * 
     * @param particle
     * @return true if the particle was successfully added to the group
     */
    public boolean add(T particle) {
        return addParticle(particle);
    }

    /**
     * Adds all the particles in the collection to this group.
     * <p>
     * The group must be playing (true by default).
     * 
     * @param particles
     * @return number of particles successfully added to the group
     */
    public int addAll(Collection<T> particles) {
        if (!worldPlayState || capacity == 0) {
            return 0;
        }
        if (this.particles.size() + particles.size() <= capacity) {
            this.particles.addAll(particles);
            for (T p : particles)
                for (ParticleDriver<T> d : drivers) {
                    d.particleAdded(this, p);
                }
            return particles.size();
        }
        int c = 0;
        for (T p : particles) {
            if (this.particles.size() >= capacity) {
                if (dynamicSizingStep >= 0) {
                    capacity = this.particles.size() + dynamicSizingStep;
                } else if (!overflow.removeParticle(this, p)) {
                    continue;
                }
            }
            if (addParticle(p))
                c++;
        }
        return c;
    }

    /**
     * Removes the particle from the group.
     * <p>
     * The group must be playing (true by default).
     * 
     * @param particle
     * @return true if the particle existed in the group
     */
    public boolean remove(T particle) {
        return worldPlayState && particles.remove(particle);
    }

    /**
     * Removes the particle at the index from the group.
     * 
     * @param i index to remove from
     * @return removed particle
     */
    public T remove(int i) {
        if (worldPlayState) {
            return particles.remove(i);
        } else {
            return null;
        }
    }

    /**
     * Clears all particles from this group.
     * <p>
     * The group must be playing (true by default).
     */
    public void clearAllParticles() {
        if (worldPlayState) {
            particles.clear();
        }
    }

    /**
     * Add a particle driver.
     * <p>
     * The driver will be added to the front of the driver stack if indicated.
     * Otherwise, the driver will be added to the rear.
     * 
     * @param driver
     * @param appendToFront
     */
    public void addDriver(ParticleDriver<T> driver, boolean appendToFront) {
        if (appendToFront) {
            drivers.addFirst(driver);
        } else {
            drivers.addLast(driver);
        }
    }

    /**
     * Adds a particle driver to the end of the driver stack.
     * 
     * @param driver
     */
    public void addDriver(ParticleDriver<T> driver) {
        addDriver(driver, false);
    }

    /**
     * Removes the driver from the driver stack.
     * 
     * @param driver
     */
    public void removeDriver(ParticleDriver<T> driver) {
        drivers.remove(driver);
    }

    /**
     * Removes all drivers from the driver stack.
     */
    public void clearAllDrivers() {
        drivers.clear();
    }

    /**
     * Set the maximum number of particles this group can handle.
     * <p>
     * Changing the capacity outside initialization can be expensive, since particle
     * geometries have to recalculate their buffers to cope with the promise of more
     * particles.
     * <p>
     * It is suggested to set the capacity using a constructor.
     * <p>
     * default=0 (no particle support)
     * 
     * @param capacity
     * @see #ParticleGroup(int)
     * @see #ParticleGroup(java.lang.String, int)
     */
    @VfxAttribute(name = "capacity")
    public void setCapacity(int capacity) {
        assert capacity >= 0 : "Group capacity must be greater than or equal to zero.";
        this.capacity = capacity;
        while (this.capacity < particles.size()) {
            overflow.removeParticle(this, null);
        }
    }

    /**
     * Sets the strategy for when an added particle would make the group's size
     * exceed its capacity.
     * <p>
     * default={@link OverflowStrategy#CullNew}
     * 
     * @param overflow
     */
    @VfxAttribute(name = "overflowStrategy")
    public void setOverflowStrategy(OverflowStrategy<T> overflow) {
        this.overflow = overflow;
    }

    /**
     * Set the default emission volume used by this particle group and subsequent
     * drivers.
     * <p>
     * default={@link EmissionPoint}
     * 
     * @param volume emission volume (not null)
     */
    @VfxAttribute(name = "volume")
    public void setVolume(EmissionVolume volume) {
        assert volume != null : "Emission volume cannot be null";
        this.volume = volume;
    }

    /**
     * Sets the rate at which particles update.
     * <p>
     * This name is propagated to drivers through {@code tpf}.<br>
     * default=1.0
     * 
     * @param speed
     */
    @Override
    public void setUpdateSpeed(float speed) {
        this.updateSpeed = speed;
    }

    /**
     * Sets the rate at which particles lose life.
     * <p>
     * Decay=1 means particles lose one "life point" each second. Decay=2, particles
     * lose two points each second. A decay of zero allows particles to exist
     * indefinitely.
     * <p>
     * default=1.0
     * 
     * @param decay
     */
    @VfxAttribute(name = "decayRate")
    public void setDecayRate(float decay) {
        this.decay = decay;
    }

    /**
     * Sets the initial delay in seconds.
     * <p>
     * default=0.0 (no delay)
     * 
     * @param delay
     */
    @Override
    public void setInitialDelay(float delay) {
        this.delay = delay;
    }

    /**
     * If true, world decay rate will be used to decay particles instead of local
     * decay rate.
     * <p>
     * default=false
     * 
     * @param inheritDecayRate
     */
    @VfxAttribute(name = "inheritDecayRate")
    public void setInheritDecayRate(boolean inheritDecayRate) {
        this.inheritDecayRate = inheritDecayRate;
    }

    /**
     * Sets this group to dynamically resize its capacity to accept more particles.
     * <p>
     * The integer value indicates how much above the necessary capacity the group's
     * capacity is set to. A dynamic sizing step of 3 would resize the group to be 3
     * greater than the current number of particles. Resizing only occurs when the
     * number of particles would exceed the group's capacity.
     * <p>
     * This feature can potentially put more strain on the system, because particle
     * geometries have to create new buffers to match the changing capacity. It is
     * recommended to set the dynamic resizing step as high as reasonably possible
     * in order to cut down of the number of reallocations the geometries must do.
     * That is, if this feature must be used at all.
     * <p>
     * To disable this feature, set the dynamic sizing step to less than zero.
     * <p>
     * default={@code -1} (disabled)
     * 
     * @param step
     */
    @VfxAttribute(name = "dynamicSizingStep")
    public void setDynamicSizingStep(int step) {
        this.dynamicSizingStep = step;
    }

    /**
     * Sets this group's play state to true.
     * <p>
     * default=play
     */
    @Override
    public void play() {
        playing = true;
    }

    /**
     * Sets this group's play state to false.
     * <p>
     * Drivers will not be updated in this state. New particles will be denied.
     * Existing particles cannot be removed.
     * <p>
     * Since this group's particles cannot, and should not, change while this group
     * is paused, any particle geometries using this group do not need to update
     * their meshes in this state.
     * <p>
     * This is an extreme method for making the simulation stop, since it stops
     * everything. A more conservative approach is to set the update speed to zero.
     * Then this group, child groups, and subsequent drivers will still get updated,
     * but they will not progress.
     * <p>
     * Call {@link #play()} to undo this.<br>
     * default=play
     * 
     * @see ParticleGeometry#setForceMeshUpdate(boolean)
     */
    @Override
    public void pause() {
        playing = false;
    }

    /**
     * Reverses the play state.
     * <p>
     * If playing (true), the play state is set to false.<br>
     * If paused (false), the play state is set to true.
     * 
     * @return the new play state
     */
    public boolean flipPlayState() {
        return (playing = !playing);
    }

    /**
     * Resets the group by removing all particles, reseting the simulation time, and
     * reseting child particle groups.
     * <p>
     * Drivers are also notified, so they can reset themselves accordingly. <br>
     * Will not reset if paused.
     */
    @VfxCommand(name = "reset")
    public void reset() {
        reset(true);
    }

    /**
     * Resets the group by removing all particles, reseting the simulation time, and
     * (optionally) reseting child particle groups.
     * <p>
     * Drivers are also notified, so they can reset themselves accordingly. <br>
     * Will not reset if paused.
     * 
     * @param resetChildren if true, child particle groups will also be reset
     */
    public void reset(boolean resetChildren) {
        if (worldPlayState) {
            particles.clear();
            time = 0;
            for (ParticleDriver<T> d : drivers) {
                d.groupReset(this);
            }
            if (resetChildren)
                for (ParticleGroup g : childGroups) {
                    g.reset(true);
                }
        }
    }

    /**
     * Gets the particle at the specified index.
     * 
     * @param i index between 0 (inclusive) and group size (exclusive)
     * @return particle at index
     */
    public T get(int i) {
        return particles.get(i);
    }

    /**
     * Gets the list of particles this group controls.
     * <p>
     * Note: {@code ParticleGroup} is iterable in this respect.
     * <p>
     * <em>Do not modify the returned list!</em>
     * 
     * @return
     */
    public List<T> getParticleList() {
        return particles;
    }

    /**
     * 
     * @return
     */
    @VfxAttribute(name = "overflowStrategy", input = false)
    public OverflowStrategy<T> getOverflowStrategy() {
        return overflow;
    }

    /**
     * 
     * @return
     */
    @VfxAttribute(name = "volume", input = false)
    public EmissionVolume getVolume() {
        return volume;
    }

    /**
     * Gets the list of child particle group's this group is a parent of.
     * <p>
     * <em>Do not modify the returned list!</em>
     * 
     * @return
     */
    public List<ParticleGroup> getChildGroupList() {
        return childGroups;
    }

    /**
     * Gets the list of drivers controlling this particle group.
     * <p>
     * <em>Do not modify the returned list!</em>
     * 
     * @return
     */
    public List<ParticleDriver<T>> getDriverList() {
        return drivers;
    }

    /**
     * Get the parent particle group of this group.
     * 
     * @return
     */
    @VfxInfo(name = "parent")
    public ParticleGroup getParentGroup() {
        return parentGroup;
    }

    /**
     * Returns true if this group has no particles.
     * 
     * @return
     */
    public boolean isEmpty() {
        return particles.isEmpty();
    }

    /**
     * Returns true if the number of particles in this group is equal to its
     * capacity.
     * 
     * @return
     */
    public boolean isFull() {
        return particles.size() >= capacity;
    }

    /**
     * Returns this group's local play state.
     * <p>
     * true=playing<br>
     * false=paused
     * 
     * @return local play state
     */
    @Override
    public boolean getLocalPlayState() {
        return playing;
    }

    /**
     * Returns true if the current time is within this group's delay zone.
     * 
     * @return
     */
    public boolean inDelayZone() {
        return time < delay;
    }

    /**
     * Returns the number of particles currently part of this group.
     * 
     * @return
     */
    @VfxInfo(name = "size")
    public int size() {
        return particles.size();
    }

    /**
     * Returns the maximum number of particles that can be part of this group.
     * 
     * @return
     */
    @VfxAttribute(name = "capacity", input = false)
    public int capacity() {
        return capacity;
    }

    /**
     * 
     * @return
     */
    @Override
    public float getUpdateSpeed() {
        return updateSpeed;
    }

    /**
     * 
     * @return
     */
    @VfxAttribute(name = "decayRate", input = false)
    public float getDecayRate() {
        return decay;
    }

    /**
     * 
     * @return
     */
    @Override
    public float getInitialDelay() {
        return delay;
    }

    /**
     * 
     * @return
     */
    @VfxAttribute(name = "inheritDecayRate", input = false)
    public boolean isInheritDecayRate() {
        return inheritDecayRate;
    }

    /**
     * 
     * @return
     */
    @VfxAttribute(name = "dynamicSizingStep", input = false)
    public int getDynamicSizingStep() {
        return dynamicSizingStep;
    }

    /**
     * Fetches the world update speed of this group.
     * 
     * @return
     */
    @Override
    public float getWorldUpdateSpeed() {
        if (parentGroup != null) {
            return parentGroup.getWorldUpdateSpeed() * updateSpeed;
        } else {
            return updateSpeed;
        }
    }

    /**
     * Fetches the world decay rate of this group.
     * 
     * @return
     */
    @VfxInfo(name = "worldDecayRate", important = false)
    public float getWorldDecayRate() {
        if (parentGroup != null) {
            return parentGroup.getWorldDecayRate() * decay;
        } else {
            return decay;
        }
    }

    /**
     * Fetches the total number of seconds which this group must wait from particle
     * system start to begin its simulation.
     * 
     * @return
     */
    @Override
    public float getWorldInitialDelay() {
        if (parentGroup != null) {
            return parentGroup.getWorldInitialDelay() + delay;
        } else {
            return delay;
        }
    }

    /**
     * Gets the world play state of this group.
     * <p>
     * If this or any parent group is <em>not</em> playing, the world play state is
     * false.
     * 
     * @return
     */
    @Override
    public boolean getWorldPlayState() {
        return worldPlayState;
    }

    /**
     * Gets the time since beginning the simulation (after world delay).
     * 
     * @return
     */
    @Override
    public float getTime() {
        return Math.max(time - delay, 0f);
    }

    /**
     * Get the time since beginning update (before world delay).
     * 
     * @return
     */
    @Override
    public float getRawTime() {
        return time;
    }

    @Override
    public Iterator<T> iterator() {
        return particles.iterator();
    }

}
